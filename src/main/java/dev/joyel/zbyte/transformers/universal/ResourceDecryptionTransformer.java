package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.defined.JVMComparisonProvider;
import dev.joyel.zbyte.executor.defined.JVMMethodProvider;
import dev.joyel.zbyte.executor.defined.MappedMethodProvider;
import dev.joyel.zbyte.executor.providers.ComparisonProvider;
import dev.joyel.zbyte.executor.providers.DelegatingProvider;
import dev.joyel.zbyte.executor.values.JavaArray;
import dev.joyel.zbyte.executor.values.JavaValue;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@TransformerConfig.ConfigOptions(configClass = ResourceDecryptionTransformer.Config.class)
public class ResourceDecryptionTransformer extends Transformer<ResourceDecryptionTransformer.Config> {
    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
            "([B)[B", "(Ljava/lang/String;[B)[B", "([BLjava/lang/String;)[B"));
    private static final Set<String> RESOURCE_READERS = new HashSet<>(Arrays.asList("getResourceAsStream", "getResource"));

    private boolean done;

    private Config settings() {
        Config config = getConfig();
        return config == null ? new Config() : config;
    }

    @Override
    public boolean transform() throws Throwable {
        if (done) {
            return false;
        }
        done = true;
        Config config = settings();
        String[] target = locate(config);
        if (target == null) {
            return false;
        }
        ClassNode owner = classes.get(target[0]);
        MethodNode method = null;
        if (owner != null) {
            for (MethodNode candidate : owner.methods) {
                if (candidate.name.equals(target[1]) && candidate.desc.equals(target[2])) {
                    method = candidate;
                }
            }
        }
        if (method == null || (method.access & ACC_STATIC) == 0) {
            logger.warn("Decryptor {}.{}{} was not found or is not static", target[0], target[1], target[2]);
            return false;
        }
        DelegatingProvider provider = newProvider();
        Map<String, byte[]> resources = getDeobfuscator().getInputPassthrough();
        List<Pattern> include = patterns(config.getInclude());
        List<Pattern> exclude = patterns(config.getExclude());
        int decrypted = 0;
        int failed = 0;
        for (String name : new ArrayList<>(resources.keySet())) {
            if (!selected(name, include, exclude)) {
                continue;
            }
            byte[] data = resources.get(name);
            byte[] plain;
            try {
                plain = decrypt(owner, method, provider, name, data, config);
            } catch (Throwable t) {
                failed++;
                logger.warn("Could not decrypt {}: {}", name, t.toString());
                continue;
            }
            if (plain == null || Arrays.equals(plain, data)) {
                continue;
            }
            if (config.isLoadClasses() && isClassFile(plain)) {
                String loadName = name.endsWith(".class") ? name : name + ".class";
                resources.remove(name);
                getDeobfuscator().loadInput(loadName, plain);
                if (resources.remove(loadName) != null) {
                    resources.put(name, plain);
                }
            } else {
                resources.put(name, plain);
            }
            decrypted++;
        }
        logger.info("Decrypted {} resources ({} failed)", decrypted, failed);
        return decrypted > 0;
    }

    private String[] locate(Config config) {
        String configured = config.getDecryptor();
        if (configured != null && !configured.isEmpty()) {
            int paren = configured.indexOf('(');
            int dot = paren == -1 ? -1 : configured.lastIndexOf('.', paren);
            if (paren == -1 || dot == -1) {
                logger.warn("Decryptor must look like pkg/Class.method([B)[B but was {}", configured);
                return null;
            }
            String desc = configured.substring(paren);
            if (!SUPPORTED.contains(desc)) {
                logger.warn("Unsupported decryptor descriptor {}; supported: {}", desc, SUPPORTED);
                return null;
            }
            return new String[]{configured.substring(0, dot).replace('.', '/'), configured.substring(dot + 1, paren), desc};
        }
        Set<String> found = new LinkedHashSet<>();
        for (ClassNode node : classNodes()) {
            for (MethodNode method : node.methods) {
                if (method.instructions == null || !readsResources(method)) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode && insn.getOpcode() == INVOKESTATIC) {
                        MethodInsnNode call = (MethodInsnNode) insn;
                        if (classes.containsKey(call.owner) && SUPPORTED.contains(call.desc)) {
                            found.add(call.owner + "." + call.name + call.desc);
                        }
                    }
                }
            }
        }
        if (found.size() != 1) {
            logger.warn("Could not pick a resource decryptor automatically (candidates: {}); set \"decryptor\"", found);
            return null;
        }
        String only = found.iterator().next();
        int paren = only.indexOf('(');
        int dot = only.lastIndexOf('.', paren);
        logger.info("Using resource decryptor {}", only);
        return new String[]{only.substring(0, dot), only.substring(dot + 1, paren), only.substring(paren)};
    }

    private boolean readsResources(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (RESOURCE_READERS.contains(call.name)
                        && (call.owner.equals("java/lang/Class") || call.owner.equals("java/lang/ClassLoader"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Pattern> patterns(List<String> expressions) {
        List<Pattern> out = new ArrayList<>();
        for (String expression : expressions) {
            out.add(Pattern.compile(expression));
        }
        return out;
    }

    private static boolean selected(String name, List<Pattern> include, List<Pattern> exclude) {
        boolean wanted;
        if (include.isEmpty()) {
            wanted = !name.endsWith(".class") && !name.startsWith("META-INF/");
        } else {
            wanted = false;
            for (Pattern pattern : include) {
                if (pattern.matcher(name).find()) {
                    wanted = true;
                    break;
                }
            }
        }
        if (!wanted) {
            return false;
        }
        for (Pattern pattern : exclude) {
            if (pattern.matcher(name).find()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isClassFile(byte[] data) {
        return data.length > 30 && (data[0] & 0xFF) == 0xCA && (data[1] & 0xFF) == 0xFE
                && (data[2] & 0xFF) == 0xBA && (data[3] & 0xFF) == 0xBE;
    }

    private byte[] decrypt(ClassNode owner, MethodNode method, DelegatingProvider provider, String name, byte[] data, Config config) {
        List<JavaValue> args = new ArrayList<>();
        for (Type type : Type.getArgumentTypes(method.desc)) {
            if (type.getSort() == Type.ARRAY) {
                args.add(new JavaArray(data.clone()));
            } else {
                args.add(JavaValue.valueOf(config.getNamePrefix() + name));
            }
        }
        Context context = new Context(provider);
        context.dictionary = classes;
        context.push(owner.name, method.name, getDeobfuscator().getConstantPool(owner).getSize());
        return MethodExecutor.execute(owner, method, args, null, context);
    }

    private DelegatingProvider newProvider() {
        DelegatingProvider provider = new DelegatingProvider();
        provider.register(new JVMMethodProvider());
        provider.register(new JVMComparisonProvider());
        provider.register(new MappedMethodProvider(classes));
        provider.register(new ComparisonProvider() {
            @Override
            public boolean instanceOf(JavaValue target, Type type, Context context) {
                return false;
            }

            @Override
            public boolean checkcast(JavaValue target, Type type, Context context) {
                return true;
            }

            @Override
            public boolean checkEquality(JavaValue first, JavaValue second, Context context) {
                return false;
            }

            @Override
            public boolean canCheckInstanceOf(JavaValue target, Type type, Context context) {
                return false;
            }

            @Override
            public boolean canCheckcast(JavaValue target, Type type, Context context) {
                return true;
            }

            @Override
            public boolean canCheckEquality(JavaValue first, JavaValue second, Context context) {
                return false;
            }
        });
        return provider;
    }

    public static class Config extends TransformerConfig {
        private String decryptor = "";
        private String namePrefix = "";
        private List<String> include = new ArrayList<>();
        private List<String> exclude = new ArrayList<>();
        private boolean loadClasses = true;

        public Config() {
            super(ResourceDecryptionTransformer.class);
        }

        public String getDecryptor() {
            return decryptor;
        }

        public void setDecryptor(String decryptor) {
            this.decryptor = decryptor;
        }

        public String getNamePrefix() {
            return namePrefix;
        }

        public void setNamePrefix(String namePrefix) {
            this.namePrefix = namePrefix == null ? "" : namePrefix;
        }

        public List<String> getInclude() {
            return include;
        }

        public void setInclude(List<String> include) {
            this.include = include == null ? new ArrayList<>() : include;
        }

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude == null ? new ArrayList<>() : exclude;
        }

        public boolean isLoadClasses() {
            return loadClasses;
        }

        public void setLoadClasses(boolean loadClasses) {
            this.loadClasses = loadClasses;
        }
    }
}
