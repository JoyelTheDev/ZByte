package dev.joyel.zbyte.transformers.universal;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.List;
import java.util.Objects;

public final class ConstantAnalysis implements Opcodes {

    private ConstantAnalysis() {
    }

    public static final class Value implements org.objectweb.asm.tree.analysis.Value {
        private final int size;
        private final Object constant;

        Value(int size, Object constant) {
            this.size = size;
            this.constant = constant;
        }

        @Override
        public int getSize() {
            return size;
        }

        public Object getConstant() {
            return constant;
        }

        public boolean isConstant() {
            return constant != null;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Value)) {
                return false;
            }
            Value value = (Value) other;
            return size == value.size && Objects.equals(constant, value.constant);
        }

        @Override
        public int hashCode() {
            return Objects.hash(size, constant);
        }
    }

    public static boolean isFoldableUnary(int opcode) {
        return (opcode >= INEG && opcode <= DNEG) || (opcode >= I2L && opcode <= I2S);
    }

    public static boolean isFoldableBinary(int opcode) {
        return (opcode >= IADD && opcode <= DREM) || (opcode >= ISHL && opcode <= LXOR) || (opcode >= LCMP && opcode <= DCMPG);
    }

    public static Object fold(int opcode, Object first, Object second) {
        try {
            if (isFoldableUnary(opcode)) {
                return foldUnary(opcode, first);
            }
            if (isFoldableBinary(opcode)) {
                return foldBinary(opcode, first, second);
            }
        } catch (ArithmeticException e) {
            return null;
        }
        return null;
    }

    private static Object foldUnary(int opcode, Object a) {
        switch (opcode) {
            case INEG:
                return a instanceof Integer ? (Object) (-(Integer) a) : null;
            case LNEG:
                return a instanceof Long ? (Object) (-(Long) a) : null;
            case FNEG:
                return a instanceof Float ? (Object) (-(Float) a) : null;
            case DNEG:
                return a instanceof Double ? (Object) (-(Double) a) : null;
            case I2L:
                return a instanceof Integer ? (Object) (long) (Integer) a : null;
            case I2F:
                return a instanceof Integer ? (Object) (float) (Integer) a : null;
            case I2D:
                return a instanceof Integer ? (Object) (double) (Integer) a : null;
            case L2I:
                return a instanceof Long ? (Object) (int) (long) (Long) a : null;
            case L2F:
                return a instanceof Long ? (Object) (float) (long) (Long) a : null;
            case L2D:
                return a instanceof Long ? (Object) (double) (long) (Long) a : null;
            case F2I:
                return a instanceof Float ? (Object) (int) (float) (Float) a : null;
            case F2L:
                return a instanceof Float ? (Object) (long) (float) (Float) a : null;
            case F2D:
                return a instanceof Float ? (Object) (double) (float) (Float) a : null;
            case D2I:
                return a instanceof Double ? (Object) (int) (double) (Double) a : null;
            case D2L:
                return a instanceof Double ? (Object) (long) (double) (Double) a : null;
            case D2F:
                return a instanceof Double ? (Object) (float) (double) (Double) a : null;
            case I2B:
                return a instanceof Integer ? (Object) (int) (byte) (int) (Integer) a : null;
            case I2C:
                return a instanceof Integer ? (Object) (int) (char) (int) (Integer) a : null;
            case I2S:
                return a instanceof Integer ? (Object) (int) (short) (int) (Integer) a : null;
            default:
                return null;
        }
    }

    private static Object foldBinary(int opcode, Object a, Object b) {
        if (a instanceof Integer && b instanceof Integer) {
            int x = (Integer) a;
            int y = (Integer) b;
            switch (opcode) {
                case IADD:
                    return x + y;
                case ISUB:
                    return x - y;
                case IMUL:
                    return x * y;
                case IDIV:
                    return x / y;
                case IREM:
                    return x % y;
                case ISHL:
                    return x << y;
                case ISHR:
                    return x >> y;
                case IUSHR:
                    return x >>> y;
                case IAND:
                    return x & y;
                case IOR:
                    return x | y;
                case IXOR:
                    return x ^ y;
                default:
                    return null;
            }
        }
        if (a instanceof Long && b instanceof Long) {
            long x = (Long) a;
            long y = (Long) b;
            switch (opcode) {
                case LADD:
                    return x + y;
                case LSUB:
                    return x - y;
                case LMUL:
                    return x * y;
                case LDIV:
                    return x / y;
                case LREM:
                    return x % y;
                case LAND:
                    return x & y;
                case LOR:
                    return x | y;
                case LXOR:
                    return x ^ y;
                case LCMP:
                    return Long.compare(x, y);
                default:
                    return null;
            }
        }
        if (a instanceof Long && b instanceof Integer) {
            long x = (Long) a;
            int y = (Integer) b;
            switch (opcode) {
                case LSHL:
                    return x << y;
                case LSHR:
                    return x >> y;
                case LUSHR:
                    return x >>> y;
                default:
                    return null;
            }
        }
        if (a instanceof Float && b instanceof Float) {
            float x = (Float) a;
            float y = (Float) b;
            switch (opcode) {
                case FADD:
                    return x + y;
                case FSUB:
                    return x - y;
                case FMUL:
                    return x * y;
                case FDIV:
                    return x / y;
                case FREM:
                    return x % y;
                case FCMPL:
                    return Float.isNaN(x) || Float.isNaN(y) ? -1 : Float.compare(x, y) == 0 || x == y ? 0 : (x < y ? -1 : 1);
                case FCMPG:
                    return Float.isNaN(x) || Float.isNaN(y) ? 1 : x == y ? 0 : (x < y ? -1 : 1);
                default:
                    return null;
            }
        }
        if (a instanceof Double && b instanceof Double) {
            double x = (Double) a;
            double y = (Double) b;
            switch (opcode) {
                case DADD:
                    return x + y;
                case DSUB:
                    return x - y;
                case DMUL:
                    return x * y;
                case DDIV:
                    return x / y;
                case DREM:
                    return x % y;
                case DCMPL:
                    return Double.isNaN(x) || Double.isNaN(y) ? -1 : x == y ? 0 : (x < y ? -1 : 1);
                case DCMPG:
                    return Double.isNaN(x) || Double.isNaN(y) ? 1 : x == y ? 0 : (x < y ? -1 : 1);
                default:
                    return null;
            }
        }
        return null;
    }

    public static int sizeOf(Object constant) {
        return constant instanceof Long || constant instanceof Double ? 2 : 1;
    }

    private static Value unknown(int size) {
        return new Value(size, null);
    }

    private static Value ofType(Type type) {
        return type.getSort() == Type.VOID ? null : unknown(type.getSize());
    }

    private static Value constant(Object value) {
        return new Value(sizeOf(value), value);
    }

    private static int binaryResultSize(int opcode) {
        switch (opcode) {
            case LADD:
            case LSUB:
            case LMUL:
            case LDIV:
            case LREM:
            case LSHL:
            case LSHR:
            case LUSHR:
            case LAND:
            case LOR:
            case LXOR:
            case DADD:
            case DSUB:
            case DMUL:
            case DDIV:
            case DREM:
                return 2;
            default:
                return 1;
        }
    }

    private static int unaryResultSize(int opcode) {
        switch (opcode) {
            case LNEG:
            case DNEG:
            case I2L:
            case I2D:
            case F2L:
            case F2D:
            case L2D:
            case D2L:
                return 2;
            default:
                return 1;
        }
    }

    private static final class Interp extends Interpreter<Value> {

        Interp() {
            super(ASM9);
        }

        @Override
        public Value newValue(Type type) {
            if (type == null) {
                return unknown(1);
            }
            return ofType(type);
        }

        @Override
        public Value newOperation(AbstractInsnNode insn) throws AnalyzerException {
            int opcode = insn.getOpcode();
            switch (opcode) {
                case ACONST_NULL:
                    return unknown(1);
                case ICONST_M1:
                case ICONST_0:
                case ICONST_1:
                case ICONST_2:
                case ICONST_3:
                case ICONST_4:
                case ICONST_5:
                    return constant(opcode - ICONST_0);
                case LCONST_0:
                    return constant(0L);
                case LCONST_1:
                    return constant(1L);
                case FCONST_0:
                    return constant(0F);
                case FCONST_1:
                    return constant(1F);
                case FCONST_2:
                    return constant(2F);
                case DCONST_0:
                    return constant(0D);
                case DCONST_1:
                    return constant(1D);
                case BIPUSH:
                case SIPUSH:
                    return constant(((IntInsnNode) insn).operand);
                case LDC: {
                    Object cst = ((LdcInsnNode) insn).cst;
                    if (cst instanceof Integer || cst instanceof Float || cst instanceof Long || cst instanceof Double || cst instanceof String) {
                        return constant(cst);
                    }
                    return unknown(1);
                }
                case JSR:
                    return unknown(1);
                case GETSTATIC:
                    return unknown(Type.getType(((FieldInsnNode) insn).desc).getSize());
                case NEW:
                    return unknown(1);
                default:
                    throw new AnalyzerException(insn, "Invalid opcode " + opcode);
            }
        }

        @Override
        public Value copyOperation(AbstractInsnNode insn, Value value) {
            return value;
        }

        @Override
        public Value unaryOperation(AbstractInsnNode insn, Value value) throws AnalyzerException {
            int opcode = insn.getOpcode();
            if (isFoldableUnary(opcode)) {
                if (value.isConstant()) {
                    Object result = fold(opcode, value.getConstant(), null);
                    if (result != null) {
                        return constant(result);
                    }
                }
                return unknown(unaryResultSize(opcode));
            }
            switch (opcode) {
                case IINC:
                    if (value.getConstant() instanceof Integer) {
                        return constant((Integer) value.getConstant() + ((IincInsnNode) insn).incr);
                    }
                    return unknown(1);
                case IFEQ:
                case IFNE:
                case IFLT:
                case IFGE:
                case IFGT:
                case IFLE:
                case TABLESWITCH:
                case LOOKUPSWITCH:
                case IRETURN:
                case LRETURN:
                case FRETURN:
                case DRETURN:
                case ARETURN:
                case PUTSTATIC:
                case ATHROW:
                case MONITORENTER:
                case MONITOREXIT:
                case IFNULL:
                case IFNONNULL:
                    return null;
                case GETFIELD:
                    return unknown(Type.getType(((FieldInsnNode) insn).desc).getSize());
                case NEWARRAY:
                case ANEWARRAY:
                case ARRAYLENGTH:
                case CHECKCAST:
                case INSTANCEOF:
                    return unknown(1);
                default:
                    throw new AnalyzerException(insn, "Invalid opcode " + opcode);
            }
        }

        @Override
        public Value binaryOperation(AbstractInsnNode insn, Value first, Value second) throws AnalyzerException {
            int opcode = insn.getOpcode();
            if (isFoldableBinary(opcode)) {
                if (first.isConstant() && second.isConstant()) {
                    Object result = fold(opcode, first.getConstant(), second.getConstant());
                    if (result != null) {
                        return constant(result);
                    }
                }
                return unknown(binaryResultSize(opcode));
            }
            switch (opcode) {
                case IALOAD:
                case BALOAD:
                case CALOAD:
                case SALOAD:
                case FALOAD:
                case AALOAD:
                    return unknown(1);
                case LALOAD:
                case DALOAD:
                    return unknown(2);
                case IF_ICMPEQ:
                case IF_ICMPNE:
                case IF_ICMPLT:
                case IF_ICMPGE:
                case IF_ICMPGT:
                case IF_ICMPLE:
                case IF_ACMPEQ:
                case IF_ACMPNE:
                case PUTFIELD:
                    return null;
                default:
                    throw new AnalyzerException(insn, "Invalid opcode " + opcode);
            }
        }

        @Override
        public Value ternaryOperation(AbstractInsnNode insn, Value first, Value second, Value third) {
            return null;
        }

        @Override
        public Value naryOperation(AbstractInsnNode insn, List<? extends Value> values) {
            if (insn instanceof MethodInsnNode) {
                return ofType(Type.getReturnType(((MethodInsnNode) insn).desc));
            }
            if (insn instanceof InvokeDynamicInsnNode) {
                return ofType(Type.getReturnType(((InvokeDynamicInsnNode) insn).desc));
            }
            return unknown(1);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, Value value, Value expected) {
        }

        @Override
        public Value merge(Value first, Value second) {
            if (first.equals(second)) {
                return first;
            }
            if (first.getSize() != second.getSize()) {
                return unknown(1);
            }
            return unknown(first.getSize());
        }
    }

    public static Frame<Value>[] analyze(ClassNode owner, MethodNode method) {
        if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0 || method.instructions.size() == 0) {
            return null;
        }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() == JSR || insn.getOpcode() == RET) {
                return null;
            }
        }
        int oldStack = method.maxStack;
        int oldLocals = method.maxLocals;
        method.maxLocals = Math.max(oldLocals, UniversalSupport.requiredLocals(method));
        try {
            int[] candidates = {Math.max(oldStack, 32), 1024};
            for (int stack : candidates) {
                method.maxStack = stack;
                try {
                    return new Analyzer<>(new Interp()).analyze(owner.name, method);
                } catch (AnalyzerException | RuntimeException e) {
                    if (stack == candidates[candidates.length - 1]) {
                        return null;
                    }
                }
            }
            return null;
        } finally {
            method.maxStack = oldStack;
            method.maxLocals = oldLocals;
        }
    }
}
