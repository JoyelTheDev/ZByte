package dev.joyel.zbyte.transformers.universal;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class UniversalNumberPoolTransformer extends AbstractPoolTransformer {

    private static char primitiveElement(String fieldDesc) {
        switch (fieldDesc) {
            case "[I":
                return 'I';
            case "[S":
                return 'S';
            case "[B":
                return 'B';
            case "[J":
                return 'J';
            case "[F":
                return 'F';
            case "[D":
                return 'D';
            default:
                return 0;
        }
    }

    private static char boxedElement(String fieldDesc) {
        switch (fieldDesc) {
            case "[Ljava/lang/Integer;":
                return 'I';
            case "[Ljava/lang/Short;":
                return 'S';
            case "[Ljava/lang/Byte;":
                return 'B';
            case "[Ljava/lang/Long;":
                return 'J';
            case "[Ljava/lang/Float;":
                return 'F';
            case "[Ljava/lang/Double;":
                return 'D';
            default:
                return 0;
        }
    }

    private static char element(String fieldDesc) {
        char primitive = primitiveElement(fieldDesc);
        return primitive != 0 ? primitive : boxedElement(fieldDesc);
    }

    @Override
    protected boolean creationMatches(AbstractInsnNode creation, String fieldDesc) {
        if (creation.getOpcode() == NEWARRAY) {
            int operand = ((IntInsnNode) creation).operand;
            char expected = primitiveElement(fieldDesc);
            switch (operand) {
                case T_INT:
                    return expected == 'I';
                case T_SHORT:
                    return expected == 'S';
                case T_BYTE:
                    return expected == 'B';
                case T_LONG:
                    return expected == 'J';
                case T_FLOAT:
                    return expected == 'F';
                case T_DOUBLE:
                    return expected == 'D';
                default:
                    return false;
            }
        }
        if (creation.getOpcode() == ANEWARRAY) {
            return boxedElement(fieldDesc) != 0 && fieldDesc.equals("[L" + ((TypeInsnNode) creation).desc + ";");
        }
        return false;
    }

    @Override
    protected Parsed readStoredValue(AbstractInsnNode start, String fieldDesc, Set<LabelNode> referenced) {
        Object value = UniversalSupport.constantValue(start);
        if (!(value instanceof Number)) {
            return null;
        }
        AbstractInsnNode next = UniversalSupport.next(start, referenced);
        boolean call = next instanceof MethodInsnNode
                && next.getOpcode() == INVOKESTATIC
                && ((MethodInsnNode) next).name.equals("valueOf")
                && ((MethodInsnNode) next).owner.startsWith("java/lang/");
        boolean boxed = !fieldDesc.isEmpty() && boxedElement(fieldDesc) != 0;
        if (call) {
            return fieldDesc.isEmpty() || boxed ? new Parsed(value, next) : null;
        }
        return boxed ? null : new Parsed(value, start);
    }

    @Override
    protected boolean isStore(AbstractInsnNode insn, String fieldDesc) {
        int opcode = insn.getOpcode();
        if (fieldDesc.isEmpty()) {
            return opcode == AASTORE || (opcode >= IASTORE && opcode <= SASTORE);
        }
        if (boxedElement(fieldDesc) != 0) {
            return opcode == AASTORE;
        }
        switch (primitiveElement(fieldDesc)) {
            case 'I':
                return opcode == IASTORE;
            case 'S':
                return opcode == SASTORE;
            case 'B':
                return opcode == BASTORE;
            case 'J':
                return opcode == LASTORE;
            case 'F':
                return opcode == FASTORE;
            case 'D':
                return opcode == DASTORE;
            default:
                return false;
        }
    }

    @Override
    protected Load readLoad(AbstractInsnNode start, String fieldDesc, Set<LabelNode> referenced) {
        if (start == null) {
            return null;
        }
        int opcode = start.getOpcode();
        char primitive = primitiveElement(fieldDesc);
        if (primitive != 0) {
            List<AbstractInsnNode> insns = new ArrayList<>(Arrays.asList(start));
            switch (primitive) {
                case 'I':
                    return opcode == IALOAD ? new Load(insns, 'I') : null;
                case 'S':
                    return opcode == SALOAD ? new Load(insns, 'I') : null;
                case 'B':
                    return opcode == BALOAD ? new Load(insns, 'I') : null;
                case 'J':
                    return opcode == LALOAD ? new Load(insns, 'J') : null;
                case 'F':
                    return opcode == FALOAD ? new Load(insns, 'F') : null;
                default:
                    return opcode == DALOAD ? new Load(insns, 'D') : null;
            }
        }
        char boxed = boxedElement(fieldDesc);
        if (boxed == 0 || opcode != AALOAD) {
            return null;
        }
        AbstractInsnNode call = UniversalSupport.next(start, referenced);
        if (call == null || call.getOpcode() != INVOKEVIRTUAL) {
            return null;
        }
        MethodInsnNode method = (MethodInsnNode) call;
        char type;
        switch (method.name + method.desc) {
            case "intValue()I":
            case "shortValue()S":
            case "byteValue()B":
                type = 'I';
                break;
            case "longValue()J":
                type = 'J';
                break;
            case "floatValue()F":
                type = 'F';
                break;
            case "doubleValue()D":
                type = 'D';
                break;
            default:
                return null;
        }
        return new Load(new ArrayList<>(Arrays.asList(start, call)), type);
    }

    @Override
    protected Object defaultValue(String fieldDesc) {
        if (primitiveElement(fieldDesc) == 0) {
            return null;
        }
        switch (primitiveElement(fieldDesc)) {
            case 'J':
                return 0L;
            case 'F':
                return 0F;
            case 'D':
                return 0D;
            default:
                return 0;
        }
    }

    @Override
    protected Object normalize(Object value, String fieldDesc) {
        if (!(value instanceof Number)) {
            return null;
        }
        Number number = (Number) value;
        switch (element(fieldDesc)) {
            case 'I':
                return number.intValue();
            case 'S':
                return (int) number.shortValue();
            case 'B':
                return (int) number.byteValue();
            case 'J':
                return number.longValue();
            case 'F':
                return number.floatValue();
            case 'D':
                return number.doubleValue();
            default:
                return null;
        }
    }
}
