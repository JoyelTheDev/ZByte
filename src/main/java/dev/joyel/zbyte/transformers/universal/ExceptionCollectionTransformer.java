package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.analysis.ClassMapTypeHierarchy;
import dev.joyel.zbyte.analysis.TypeHierarchy;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ExceptionCollectionTransformer extends Transformer<TransformerConfig> {

    private final Set<String> thrownExceptions = new HashSet<>();

    public ExceptionCollectionTransformer() {
        thrownExceptions.add("java/lang/Throwable");
        thrownExceptions.add("java/lang/Exception");
    }

    @Override
    public boolean transform() throws Throwable {
        int before = thrownExceptions.size();
        thrownExceptions.addAll(collect(classes, classpath));
        return thrownExceptions.size() != before;
    }

    public Set<String> getThrownExceptions() {
        return thrownExceptions;
    }

    public static Set<String> collect(Map<String, ClassNode> classes, Map<String, ClassNode> classpath) {
        Set<String> thrown = new HashSet<>();
        thrown.add("java/lang/Throwable");
        thrown.add("java/lang/Exception");
        TypeHierarchy hierarchy = new ClassMapTypeHierarchy(classes, classpath);
        for (ClassNode classNode : classes.values()) {
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof TypeInsnNode) {
                        TypeInsnNode typeInsn = (TypeInsnNode) insn;
                        if (typeInsn.getOpcode() == Opcodes.NEW) {
                            collectConstructed(thrown, classes, classpath, hierarchy, typeInsn.desc);
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode) {
                        for (Object argument : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                            collectHandle(thrown, classes, classpath, hierarchy, argument);
                        }
                    } else if (insn instanceof LdcInsnNode) {
                        collectHandle(thrown, classes, classpath, hierarchy, ((LdcInsnNode) insn).cst);
                    }
                }
            }
        }
        return thrown;
    }

    private static void collectHandle(Set<String> thrown, Map<String, ClassNode> classes, Map<String, ClassNode> classpath, TypeHierarchy hierarchy, Object value) {
        if (value instanceof Handle && ((Handle) value).getTag() == Opcodes.H_NEWINVOKESPECIAL) {
            collectConstructed(thrown, classes, classpath, hierarchy, ((Handle) value).getOwner());
        }
    }

    private static void collectConstructed(Set<String> thrown, Map<String, ClassNode> classes, Map<String, ClassNode> classpath, TypeHierarchy hierarchy, String type) {
        if (!hierarchy.isAssignableFrom("java/lang/Throwable", type)) {
            return;
        }
        String current = type;
        while (current != null && thrown.add(current)) {
            current = hierarchy.getSuperName(current);
        }
    }
}
