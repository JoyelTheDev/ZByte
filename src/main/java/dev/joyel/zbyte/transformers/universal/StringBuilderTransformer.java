package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.TransformerHelper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class StringBuilderTransformer extends Transformer<TransformerConfig> {

    private static final String BUILDER = "java/lang/StringBuilder";

    @Override
    public boolean transform() throws Throwable {
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                Set<LabelNode> referenced = UniversalSupport.referencedLabels(methodNode);
                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    if (insn.getOpcode() == NEW && ((TypeInsnNode) insn).desc.equals(BUILDER)) {
                        changed |= fold(methodNode, referenced, insn);
                    }
                }
            }
        }
        return changed;
    }

    private boolean fold(MethodNode methodNode, Set<LabelNode> referenced, AbstractInsnNode start) {
        List<AbstractInsnNode> consumed = new ArrayList<>();
        consumed.add(start);
        AbstractInsnNode dup = UniversalSupport.next(start, referenced);
        if (dup == null || dup.getOpcode() != DUP) {
            return false;
        }
        consumed.add(dup);
        StringBuilder text = new StringBuilder();
        AbstractInsnNode cursor = UniversalSupport.next(dup, referenced);
        if (TransformerHelper.isInvokeSpecial(cursor, BUILDER, "<init>", "()V")) {
            consumed.add(cursor);
        } else {
            Object initial = UniversalSupport.constantValue(cursor);
            AbstractInsnNode init = UniversalSupport.next(cursor, referenced);
            if (!(initial instanceof String) || !TransformerHelper.isInvokeSpecial(init, BUILDER, "<init>", "(Ljava/lang/String;)V")) {
                return false;
            }
            text.append((String) initial);
            consumed.add(cursor);
            consumed.add(init);
        }
        cursor = UniversalSupport.next(consumed.get(consumed.size() - 1), referenced);
        while (cursor != null) {
            if (TransformerHelper.isInvokeVirtual(cursor, BUILDER, "toString", "()Ljava/lang/String;")) {
                for (AbstractInsnNode node : consumed) {
                    methodNode.instructions.remove(node);
                }
                methodNode.instructions.set(cursor, new LdcInsnNode(text.toString()));
                return true;
            }
            Object value = UniversalSupport.constantValue(cursor);
            AbstractInsnNode append = UniversalSupport.next(cursor, referenced);
            if (value == null || !(append instanceof MethodInsnNode) || append.getOpcode() != INVOKEVIRTUAL) {
                return false;
            }
            MethodInsnNode call = (MethodInsnNode) append;
            if (!call.owner.equals(BUILDER) || !call.name.equals("append")) {
                return false;
            }
            String piece = stringify(call.desc, value);
            if (piece == null) {
                return false;
            }
            text.append(piece);
            consumed.add(cursor);
            consumed.add(append);
            cursor = UniversalSupport.next(append, referenced);
        }
        return false;
    }

    private static String stringify(String desc, Object value) {
        switch (desc) {
            case "(Ljava/lang/String;)Ljava/lang/StringBuilder;":
            case "(Ljava/lang/Object;)Ljava/lang/StringBuilder;":
                return value instanceof String ? (String) value : null;
            case "(I)Ljava/lang/StringBuilder;":
                return value instanceof Integer ? Integer.toString((Integer) value) : null;
            case "(C)Ljava/lang/StringBuilder;":
                return value instanceof Integer ? String.valueOf((char) (int) (Integer) value) : null;
            case "(Z)Ljava/lang/StringBuilder;":
                return value instanceof Integer ? Boolean.toString((Integer) value != 0) : null;
            case "(J)Ljava/lang/StringBuilder;":
                return value instanceof Long ? Long.toString((Long) value) : null;
            case "(F)Ljava/lang/StringBuilder;":
                return value instanceof Float ? Float.toString((Float) value) : null;
            case "(D)Ljava/lang/StringBuilder;":
                return value instanceof Double ? Double.toString((Double) value) : null;
            default:
                return null;
        }
    }
}
