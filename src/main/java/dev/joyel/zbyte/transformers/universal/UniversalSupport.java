package dev.joyel.zbyte.transformers.universal;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class UniversalSupport implements Opcodes {

    private UniversalSupport() {
    }

    public static Set<LabelNode> referencedLabels(MethodNode method) {
        Set<LabelNode> labels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof JumpInsnNode) {
                labels.add(((JumpInsnNode) insn).label);
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode node = (TableSwitchInsnNode) insn;
                labels.add(node.dflt);
                labels.addAll(node.labels);
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode node = (LookupSwitchInsnNode) insn;
                labels.add(node.dflt);
                labels.addAll(node.labels);
            }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            labels.add(block.start);
            labels.add(block.end);
            labels.add(block.handler);
        }
        return labels;
    }

    public static AbstractInsnNode next(AbstractInsnNode insn, Set<LabelNode> referenced) {
        AbstractInsnNode current = insn == null ? null : insn.getNext();
        while (current != null && isTransparent(current, referenced)) {
            current = current.getNext();
        }
        return current;
    }

    public static AbstractInsnNode previous(AbstractInsnNode insn, Set<LabelNode> referenced) {
        AbstractInsnNode current = insn == null ? null : insn.getPrevious();
        while (current != null && isTransparent(current, referenced)) {
            current = current.getPrevious();
        }
        return current;
    }

    private static boolean isTransparent(AbstractInsnNode insn, Set<LabelNode> referenced) {
        if (insn instanceof LineNumberNode || insn instanceof FrameNode) {
            return true;
        }
        return insn instanceof LabelNode && !referenced.contains(insn);
    }

    public static Object constantValue(AbstractInsnNode insn) {
        if (insn == null) {
            return null;
        }
        int opcode = insn.getOpcode();
        switch (opcode) {
            case ICONST_M1:
            case ICONST_0:
            case ICONST_1:
            case ICONST_2:
            case ICONST_3:
            case ICONST_4:
            case ICONST_5:
                return opcode - ICONST_0;
            case LCONST_0:
                return 0L;
            case LCONST_1:
                return 1L;
            case FCONST_0:
                return 0F;
            case FCONST_1:
                return 1F;
            case FCONST_2:
                return 2F;
            case DCONST_0:
                return 0D;
            case DCONST_1:
                return 1D;
            case BIPUSH:
            case SIPUSH:
                return ((IntInsnNode) insn).operand;
            case LDC: {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Integer || cst instanceof Long || cst instanceof Float || cst instanceof Double || cst instanceof String) {
                    return cst;
                }
                return null;
            }
            default:
                return null;
        }
    }

    public static Integer intConstant(AbstractInsnNode insn) {
        Object value = constantValue(insn);
        return value instanceof Integer ? (Integer) value : null;
    }

    public static AbstractInsnNode constantInsn(Object value) {
        if (value instanceof Integer) {
            int v = (Integer) value;
            if (v >= -1 && v <= 5) {
                return new InsnNode(ICONST_0 + v);
            }
            if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
                return new IntInsnNode(BIPUSH, v);
            }
            if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
                return new IntInsnNode(SIPUSH, v);
            }
            return new LdcInsnNode(v);
        }
        if (value instanceof Long) {
            long v = (Long) value;
            if (v == 0L) {
                return new InsnNode(LCONST_0);
            }
            if (v == 1L) {
                return new InsnNode(LCONST_1);
            }
            return new LdcInsnNode(v);
        }
        if (value instanceof Float) {
            float v = (Float) value;
            if (Float.floatToRawIntBits(v) == Float.floatToRawIntBits(0F)) {
                return new InsnNode(FCONST_0);
            }
            if (v == 1F) {
                return new InsnNode(FCONST_1);
            }
            if (v == 2F) {
                return new InsnNode(FCONST_2);
            }
            return new LdcInsnNode(v);
        }
        if (value instanceof Double) {
            double v = (Double) value;
            if (Double.doubleToRawLongBits(v) == Double.doubleToRawLongBits(0D)) {
                return new InsnNode(DCONST_0);
            }
            if (v == 1D) {
                return new InsnNode(DCONST_1);
            }
            return new LdcInsnNode(v);
        }
        return new LdcInsnNode(value);
    }

    public static boolean isFieldRef(AbstractInsnNode insn, int opcode, String owner, String name, String desc) {
        if (insn == null || insn.getOpcode() != opcode || !(insn instanceof FieldInsnNode)) {
            return false;
        }
        FieldInsnNode field = (FieldInsnNode) insn;
        return field.owner.equals(owner) && field.name.equals(name) && field.desc.equals(desc);
    }

    public static int requiredLocals(MethodNode method) {
        int locals = org.objectweb.asm.Type.getArgumentsAndReturnSizes(method.desc) >> 2;
        if ((method.access & ACC_STATIC) != 0) {
            locals -= 1;
        }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof org.objectweb.asm.tree.VarInsnNode) {
                int opcode = insn.getOpcode();
                int size = opcode == LLOAD || opcode == DLOAD || opcode == LSTORE || opcode == DSTORE ? 2 : 1;
                locals = Math.max(locals, ((org.objectweb.asm.tree.VarInsnNode) insn).var + size);
            } else if (insn instanceof org.objectweb.asm.tree.IincInsnNode) {
                locals = Math.max(locals, ((org.objectweb.asm.tree.IincInsnNode) insn).var + 1);
            }
        }
        return locals;
    }

    public static org.objectweb.asm.tree.ClassNode readRuntimeClass(String internalName) {
        try (java.io.InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            int flags = org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG;
            try {
                org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(bytes).accept(node, flags);
                return node;
            } catch (IllegalArgumentException unsupported) {
                bytes[6] = 0;
                bytes[7] = 65;
                org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(bytes).accept(node, flags);
                return node;
            }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
