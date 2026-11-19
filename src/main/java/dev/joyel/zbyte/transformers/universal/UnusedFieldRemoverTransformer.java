package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@TransformerConfig.ConfigOptions(configClass = UnusedFieldRemoverTransformer.Config.class)
public class UnusedFieldRemoverTransformer extends Transformer<UnusedFieldRemoverTransformer.Config> {
    private static final class Write {
        final MethodNode method;
        final FieldInsnNode insn;

        Write(MethodNode method, FieldInsnNode insn) {
            this.method = method;
            this.insn = insn;
        }
    }

    private static final String LIBRARY = "\u0000library";

    private final Set<String> reads = new HashSet<>();
    private final Set<String> unattributed = new HashSet<>();
    private final Set<String> strings = new HashSet<>();
    private final Map<String, List<Write>> writes = new HashMap<>();

    private Config settings() {
        Config config = getConfig();
        return config == null ? new Config() : config;
    }

    @Override
    public boolean transform() throws Throwable {
        Config config = settings();
        reads.clear();
        unattributed.clear();
        strings.clear();
        writes.clear();
        for (ClassNode node : classNodes()) {
            scan(node);
        }
        boolean changed = false;
        for (ClassNode node : classNodes()) {
            if ("java/lang/Record".equals(node.superName)) {
                continue;
            }
            boolean enumClass = (node.access & ACC_ENUM) != 0;
            boolean exposedClass = (node.access & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
            boolean serializable = config.isKeepSerializable() && isSerializable(node);
            List<FieldNode> doomed = new ArrayList<>();
            for (FieldNode field : node.fields) {
                if ((field.access & ACC_ENUM) != 0 || field.name.equals("serialVersionUID") || enumClass && field.name.equals("$VALUES")) {
                    continue;
                }
                if (field.visibleAnnotations != null && !field.visibleAnnotations.isEmpty()
                        || field.invisibleAnnotations != null && !field.invisibleAnnotations.isEmpty()) {
                    continue;
                }
                if (strings.contains(field.name) || unattributed.contains(field.name + field.desc)) {
                    continue;
                }
                if (exposedClass && (field.access & (ACC_PUBLIC | ACC_PROTECTED)) != 0 && !config.isRemoveExposed()) {
                    continue;
                }
                if (serializable && (field.access & (ACC_STATIC | ACC_TRANSIENT)) == 0) {
                    continue;
                }
                String key = node.name + "." + field.name + field.desc;
                if (reads.contains(key)) {
                    continue;
                }
                if (writes.containsKey(key) && !config.isRemoveWriteOnly()) {
                    continue;
                }
                doomed.add(field);
            }
            for (FieldNode field : doomed) {
                String key = node.name + "." + field.name + field.desc;
                List<Write> stores = writes.get(key);
                if (stores != null) {
                    for (Write store : stores) {
                        neutralize(store);
                    }
                }
                node.fields.remove(field);
                logger.debug("Removing unused field {}.{}", node.name, field.name);
                changed = true;
            }
        }
        return changed;
    }

    private void neutralize(Write store) {
        InsnList list = store.method.instructions;
        int size = Type.getType(store.insn.desc).getSize();
        list.insertBefore(store.insn, new InsnNode(size == 2 ? POP2 : POP));
        if (store.insn.getOpcode() == PUTFIELD) {
            list.insertBefore(store.insn, new InsnNode(POP));
        }
        list.remove(store.insn);
    }

    private void scan(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) insn;
                    String declaring = resolve(field.owner, field.name, field.desc);
                    if (declaring == null) {
                        unattributed.add(field.name + field.desc);
                        continue;
                    }
                    if (declaring.equals(LIBRARY)) {
                        continue;
                    }
                    String key = declaring + "." + field.name + field.desc;
                    if (field.getOpcode() == GETFIELD || field.getOpcode() == GETSTATIC) {
                        reads.add(key);
                    } else {
                        writes.computeIfAbsent(key, k -> new ArrayList<>()).add(new Write(method, field));
                    }
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                    scanConstant(indy.bsm);
                    if (indy.bsmArgs != null) {
                        for (Object arg : indy.bsmArgs) {
                            scanConstant(arg);
                        }
                    }
                } else if (insn instanceof LdcInsnNode) {
                    scanConstant(((LdcInsnNode) insn).cst);
                }
            }
        }
    }

    private void scanConstant(Object constant) {
        if (constant instanceof String) {
            strings.add((String) constant);
        } else if (constant instanceof Handle) {
            Handle handle = (Handle) constant;
            int tag = handle.getTag();
            if (tag >= H_GETFIELD && tag <= H_PUTSTATIC) {
                String declaring = resolve(handle.getOwner(), handle.getName(), handle.getDesc());
                if (declaring == null) {
                    unattributed.add(handle.getName() + handle.getDesc());
                } else if (!declaring.equals(LIBRARY)) {
                    reads.add(declaring + "." + handle.getName() + handle.getDesc());
                }
            }
        } else if (constant instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) constant;
            scanConstant(dynamic.getBootstrapMethod());
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                scanConstant(dynamic.getBootstrapMethodArgument(i));
            }
        }
    }

    private ClassNode lookup(String name) {
        ClassNode node = classes.get(name);
        return node != null ? node : classpath.get(name);
    }

    private String resolve(String owner, String name, String desc) {
        Set<String> visited = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(owner);
        while (!todo.isEmpty()) {
            String current = todo.poll();
            if (!visited.add(current)) {
                continue;
            }
            ClassNode node = lookup(current);
            if (node == null) {
                return null;
            }
            for (FieldNode field : node.fields) {
                if (field.name.equals(name) && field.desc.equals(desc)) {
                    return classes.containsKey(current) ? current : LIBRARY;
                }
            }
            if (node.interfaces != null) {
                todo.addAll(node.interfaces);
            }
            if (node.superName != null) {
                todo.add(node.superName);
            }
        }
        return null;
    }

    private boolean isSerializable(ClassNode node) {
        Set<String> visited = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(node.name);
        while (!todo.isEmpty()) {
            String current = todo.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals("java/io/Serializable")) {
                return true;
            }
            ClassNode known = lookup(current);
            if (known == null) {
                if (isRuntimeSerializable(current)) {
                    return true;
                }
                continue;
            }
            if (known.interfaces != null) {
                todo.addAll(known.interfaces);
            }
            if (known.superName != null) {
                todo.add(known.superName);
            }
        }
        return false;
    }

    private static boolean isRuntimeSerializable(String internalName) {
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            return java.io.Serializable.class.isAssignableFrom(type);
        } catch (Throwable e) {
            return false;
        }
    }

    public static class Config extends TransformerConfig {
        private boolean removeWriteOnly = true;
        private boolean removeExposed = false;
        private boolean keepSerializable = true;

        public Config() {
            super(UnusedFieldRemoverTransformer.class);
        }

        public boolean isRemoveWriteOnly() {
            return removeWriteOnly;
        }

        public void setRemoveWriteOnly(boolean removeWriteOnly) {
            this.removeWriteOnly = removeWriteOnly;
        }

        public boolean isRemoveExposed() {
            return removeExposed;
        }

        public void setRemoveExposed(boolean removeExposed) {
            this.removeExposed = removeExposed;
        }

        public boolean isKeepSerializable() {
            return keepSerializable;
        }

        public void setKeepSerializable(boolean keepSerializable) {
            this.keepSerializable = keepSerializable;
        }
    }
}
