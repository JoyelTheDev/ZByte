package dev.joyel.zbyte.executor.insn.handlers;

import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.values.JavaBoolean;
import dev.joyel.zbyte.executor.values.JavaByte;
import dev.joyel.zbyte.executor.values.JavaCharacter;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaFloat;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaLong;
import dev.joyel.zbyte.executor.values.JavaShort;
import dev.joyel.zbyte.executor.values.JavaTop;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.function.BiFunction;

public final class StackOps {
    private static final boolean VERIFY = MethodExecutor.VERIFY;

    private StackOps() {
    }

    @FunctionalInterface
    public interface ReferencePush {
        void push(List<JavaValue> stack, Object provided);
    }

    public static void push(List<JavaValue> stack, int sort, Object provided, ReferencePush reference) {
        switch (sort) {
            case Type.BOOLEAN:
                stack.add(0, new JavaBoolean((Boolean) provided));
                break;
            case Type.CHAR:
                stack.add(0, new JavaCharacter((Character) provided));
                break;
            case Type.BYTE:
                stack.add(0, new JavaByte((Byte) provided));
                break;
            case Type.SHORT:
                stack.add(0, new JavaShort((Short) provided));
                break;
            case Type.INT:
                stack.add(0, new JavaInteger((Integer) provided));
                break;
            case Type.FLOAT:
                stack.add(0, new JavaFloat((Float) provided));
                break;
            case Type.LONG:
                stack.add(0, new JavaLong((Long) provided));
                stack.add(0, new JavaTop());
                break;
            case Type.DOUBLE:
                stack.add(0, new JavaDouble((Double) provided));
                stack.add(0, new JavaTop());
                break;
            case Type.ARRAY:
            case Type.OBJECT:
                reference.push(stack, provided);
                break;
            default:
                break;
        }
    }

    public static void integerMath(List<JavaValue> stack, BiFunction<Integer, Integer, Integer> action) {
        if (VERIFY && stack.size() < 2) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue b = stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaInteger(action.apply(a.intValue(), b.intValue())));
    }

    public static void longMath(List<JavaValue> stack, BiFunction<Long, Long, Long> action) {
        if (stack.size() < 4) {
            throw new ExecutionException("Stack underflow");
        }
        stack.remove(0);
        JavaValue b = stack.remove(0);
        stack.remove(0);
        JavaValue a = stack.remove(0);
        if (!(a instanceof JavaLong) || !(b instanceof JavaLong)) {
            throw new ExecutionException("Expected two JavaLongs");
        }
        stack.add(0, new JavaLong(action.apply(a.longValue(), b.longValue())));
        stack.add(0, new JavaTop());
    }

    public static void longShift(List<JavaValue> stack, BiFunction<Long, Integer, Long> action) {
        if (stack.size() < 3) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue b = stack.remove(0);
        stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaLong(action.apply(a.longValue(), b.intValue())));
        stack.add(0, new JavaTop());
    }

    public static void longCompare(List<JavaValue> stack, BiFunction<Long, Long, Integer> action) {
        if (VERIFY && stack.size() < 4) {
            throw new ExecutionException("Stack underflow");
        }
        stack.remove(0);
        JavaValue b = stack.remove(0);
        stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaInteger(action.apply(a.longValue(), b.longValue())));
    }

    public static void doubleMath(List<JavaValue> stack, BiFunction<Double, Double, Double> action) {
        if (VERIFY && stack.size() < 4) {
            throw new ExecutionException("Stack underflow");
        }
        stack.remove(0);
        JavaValue b = stack.remove(0);
        stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaDouble(action.apply(a.doubleValue(), b.doubleValue())));
        stack.add(0, new JavaTop());
    }

    public static void doubleCompare(List<JavaValue> stack, BiFunction<Double, Double, Integer> action) {
        if (VERIFY && stack.size() < 4) {
            throw new ExecutionException("Stack underflow");
        }
        stack.remove(0);
        JavaValue b = stack.remove(0);
        stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaInteger(action.apply(a.doubleValue(), b.doubleValue())));
    }

    public static void floatMath(List<JavaValue> stack, BiFunction<Float, Float, Float> action) {
        if (VERIFY && stack.size() < 2) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue b = stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaFloat(action.apply(a.floatValue(), b.floatValue())));
    }

    public static void floatCompare(List<JavaValue> stack, BiFunction<Float, Float, Integer> action) {
        if (VERIFY && stack.size() < 2) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue b = stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaInteger(action.apply(a.floatValue(), b.floatValue())));
    }

    public static JavaValue popWide(List<JavaValue> stack) {
        JavaValue value = stack.remove(0);
        if (value instanceof JavaTop) {
            value = stack.remove(0);
            if (VERIFY && !(value instanceof JavaDouble) && !(value instanceof JavaLong)) {
                throw new ExecutionException("JavaTop not followed by JavaLong or JavaDouble");
            }
        }
        return value;
    }
}
