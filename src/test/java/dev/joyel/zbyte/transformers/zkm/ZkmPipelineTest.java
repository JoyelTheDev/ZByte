package dev.joyel.zbyte.transformers.zkm;

import dev.joyel.zbyte.analysis.ClassMapTypeHierarchy;
import dev.joyel.zbyte.analysis.TypeHierarchy;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.CallResultInliningTransformer;
import dev.joyel.zbyte.transformers.universal.InvokeDynamicInliningTransformer;
import dev.joyel.zbyte.transformers.universal.OpaqueConstantFoldingTransformer;
import dev.joyel.zbyte.transformers.universal.VariableFoldingTransformer;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZkmPipelineTest {

    private static Map<String, byte[]> readJar(String resource) throws Exception {
        InputStream in = ZkmPipelineTest.class.getResourceAsStream(resource);
        Assume.assumeTrue("missing " + resource, in != null);
        java.io.File temp = java.io.File.createTempFile("zkm", ".jar");
        temp.deleteOnExit();
        try (in; java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
            in.transferTo(out);
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(temp)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    entries.put(entry.getName().substring(0, entry.getName().length() - ".class".length()), zip.getInputStream(entry).readAllBytes());
                }
            }
        }
        return entries;
    }

    private static Map<String, ClassNode> parse(Map<String, byte[]> entries) {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (byte[] bytes : entries.values()) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            classes.put(node.name, node);
        }
        return classes;
    }

    private static boolean run(Class<? extends Transformer> type, Map<String, ClassNode> classes) throws Throwable {
        Transformer<?> transformer = type.getDeclaredConstructor().newInstance();
        transformer.init(null, TransformerConfig.configFor(type), classes, classes, new HashMap<ClassNode, ClassReader>());
        return transformer.transform();
    }

    private static Map<String, byte[]> write(Map<String, ClassNode> classes) {
        TypeHierarchy hierarchy = new ClassMapTypeHierarchy(classes, classes);
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (ClassNode node : classes.values()) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String a, String b) {
                    if (a.equals(b)) {
                        return a;
                    }
                    if (hierarchy.isAssignableFrom(a, b)) {
                        return a;
                    }
                    if (hierarchy.isAssignableFrom(b, a)) {
                        return b;
                    }
                    if (hierarchy.isInterface(a) || hierarchy.isInterface(b)) {
                        return "java/lang/Object";
                    }
                    String current = a;
                    while (current != null && !hierarchy.isAssignableFrom(current, b)) {
                        current = hierarchy.getSuperName(current);
                    }
                    return current == null ? "java/lang/Object" : current;
                }
            };
            node.accept(writer);
            out.put(node.name, writer.toByteArray());
        }
        return out;
    }

    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes) {
            super(ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String runMain(Map<String, byte[]> classes, String mainClass) throws Exception {
        Loader loader = new Loader(classes);
        Method main = loader.loadClass(mainClass).getMethod("main", String[].class);
        PrintStream previous = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
        try {
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(previous);
        }
        return captured.toString();
    }

    private static int zkmSites(Map<String, ClassNode> classes) {
        int count = 0;
        for (ClassNode node : classes.values()) {
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof InvokeDynamicInsnNode indy && ZkmInvokeDynamicResolver.isZkmSite(indy)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static void check(String resource) throws Throwable {
        String expected = runMain(readJar("/zkm/obf-sample-zkm-26-3e-original.jar"), "dev.lvstrng.Main");
        Map<String, byte[]> obfuscated = readJar(resource);
        Map<String, ClassNode> classes = parse(obfuscated);
        assertTrue(zkmSites(classes) > 0);
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            changed |= run(ZkmParameterUnpackingTransformer.class, classes);
            changed |= run(OpaqueConstantFoldingTransformer.class, classes);
            changed |= run(VariableFoldingTransformer.class, classes);
            changed |= run(InvokeDynamicInliningTransformer.class, classes);
            changed |= run(CallResultInliningTransformer.class, classes);
            changed |= run(ZkmDecryptionCleanupTransformer.class, classes);
            if (!changed) {
                break;
            }
        }
        assertEquals(0, zkmSites(classes));
        boolean readableString = false;
        for (ClassNode node : classes.values()) {
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LdcInsnNode ldc && "Executing tests...".equals(ldc.cst)) {
                        readableString = true;
                    }
                }
            }
        }
        assertTrue(readableString);
        assertEquals(expected, runMain(write(classes), "a"));
    }

    @Test
    public void sharedSampleOne() throws Throwable {
        check("/zkm/obf-sample-zkm-26-3e-shared-1.jar");
    }

    @Test
    public void sharedSampleThree() throws Throwable {
        check("/zkm/obf-sample-zkm-26-3e-shared-3.jar");
    }
}
