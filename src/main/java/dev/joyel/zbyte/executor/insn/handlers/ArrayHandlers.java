package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.insn.ExecutionFrame;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaArray;
import dev.joyel.zbyte.executor.values.JavaBoolean;
import dev.joyel.zbyte.executor.values.JavaByte;
import dev.joyel.zbyte.executor.values.JavaCharacter;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaFloat;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaLong;
import dev.joyel.zbyte.executor.values.JavaObject;
import dev.joyel.zbyte.executor.values.JavaShort;
import dev.joyel.zbyte.executor.values.JavaTop;
import dev.joyel.zbyte.executor.values.JavaValue;
import dev.joyel.zbyte.utils.PrimitiveUtils;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

public final class ArrayHandlers {
    private static final boolean VERIFY = MethodExecutor.VERIFY;

    private ArrayHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set((f, i) -> load(f.stack, int.class), IALOAD);
        dispatcher.set((f, i) -> load(f.stack, long.class), LALOAD);
        dispatcher.set((f, i) -> load(f.stack, float.class), FALOAD);
        dispatcher.set((f, i) -> load(f.stack, double.class), DALOAD);
        dispatcher.set((f, i) -> load(f.stack, Object.class), AALOAD);
        dispatcher.set((f, i) -> load(f.stack, byte.class), BALOAD);
        dispatcher.set((f, i) -> load(f.stack, char.class), CALOAD);
        dispatcher.set((f, i) -> load(f.stack, short.class), SALOAD);
        dispatcher.set((f, i) -> store(f.stack), IASTORE, LASTORE, FASTORE, DASTORE, BASTORE, CASTORE, SASTORE, AASTORE);
        dispatcher.set(ArrayHandlers::newPrimitiveArray, NEWARRAY);
        dispatcher.set((f, i) -> {
            int len = f.stack.remove(0).intValue();
            f.stack.add(0, new JavaArray(new Object[len]));
        }, ANEWARRAY);
        dispatcher.set((f, i) -> {
            JavaValue obj = f.stack.remove(0);
            if (VERIFY && obj.value() == null) {
                throw new ExecutionException("Array is null");
            }
            f.stack.add(0, new JavaInteger(Array.getLength(obj.value())));
        }, ARRAYLENGTH);
        dispatcher.set(ArrayHandlers::newMultiArray, MULTIANEWARRAY);
    }

    private static void load(List<JavaValue> stack, Class<?> type) {
        if (VERIFY && stack.size() < 2) {
            throw new ExecutionException("Stack underflow");
        }
        if (VERIFY && !(stack.get(0) instanceof JavaInteger)) {
            throw new ExecutionException("Expected Integer on stack");
        }
        if (VERIFY && !(stack.get(1) instanceof JavaObject)) {
            throw new ExecutionException("Expected Object on stack");
        }
        int index = stack.remove(0).intValue();
        JavaValue arrValue = stack.remove(0);
        Object array = arrValue.value();
        if (VERIFY && array == null) {
            throw new ExecutionException("Array is null");
        }
        if (VERIFY && !array.getClass().isArray()) {
            throw new ExecutionException("Expected Array on stack");
        }
        Object value = Array.get(array, index);
        JavaValue result;
        switch (type.getCanonicalName()) {
            case "byte":
                if (value instanceof Byte) {
                    result = new JavaByte((Byte) value);
                } else if (value instanceof Boolean) {
                    result = new JavaBoolean((Boolean) value);
                } else {
                    throw new IllegalStateException(value.getClass().getName());
                }
                break;
            case "char":
                result = new JavaCharacter((Character) value);
                break;
            case "double":
                result = new JavaDouble((Double) value);
                break;
            case "float":
                result = new JavaFloat((Float) value);
                break;
            case "int":
                result = new JavaInteger((Integer) value);
                break;
            case "long":
                result = new JavaLong((Long) value);
                break;
            case "short":
                result = new JavaShort((Short) value);
                break;
            default:
                if (value != null && value.getClass().isArray()) {
                    result = new JavaArray(value);
                } else {
                    result = new JavaObject(value, ((JavaArray) arrValue).getValueType(index));
                }
                break;
        }
        stack.add(0, result);
        if (result instanceof JavaDouble || result instanceof JavaLong) {
            stack.add(0, new JavaTop());
        }
    }

    private static void store(List<JavaValue> stack) {
        if (VERIFY && stack.size() < 3) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue value = StackOps.popWide(stack);
        JavaValue index = stack.remove(0);
        if (VERIFY && !(index instanceof JavaInteger)) {
            throw new ExecutionException("Expected Integer");
        }
        JavaValue array = stack.remove(0);
        if (VERIFY && !array.value().getClass().isArray()) {
            throw new ExecutionException("Expected Array");
        }
        Object val = MethodExecutor.value(value);
        Object target = array.value();
        int position = index.intValue();
        if (target instanceof boolean[]) {
            if (value instanceof JavaInteger) {
                Array.set(target, position, value.intValue() != 0);
            } else {
                Array.set(target, position, val);
            }
        } else if (target instanceof char[]) {
            if (value instanceof JavaInteger) {
                Array.set(target, position, (char) value.intValue());
            } else if (value instanceof JavaByte) {
                Array.set(target, position, (char) ((JavaByte) value).byteValue());
            } else if (value instanceof JavaShort) {
                Array.set(target, position, (char) ((JavaShort) value).shortValue());
            } else if (value instanceof JavaLong) {
                Array.set(target, position, (char) value.longValue());
            } else {
                Array.set(target, position, val);
            }
        } else if (target instanceof byte[]) {
            if (value instanceof JavaShort) {
                Array.set(target, position, (byte) ((JavaShort) value).shortValue());
            } else if (value instanceof JavaInteger) {
                Array.set(target, position, (byte) value.intValue());
            } else {
                Array.set(target, position, val);
            }
        } else if (target instanceof short[]) {
            if (value instanceof JavaByte) {
                Array.set(target, position, (short) ((JavaByte) value).byteValue());
            } else if (value instanceof JavaInteger) {
                Array.set(target, position, (short) value.intValue());
            } else {
                Array.set(target, position, val);
            }
        } else {
            Array.set(target, position, val);
        }
        if (value instanceof JavaObject) {
            ((JavaArray) array).onValueStored(position, value.type());
        }
    }

    private static void newPrimitiveArray(ExecutionFrame f, AbstractInsnNode insn) {
        int len = f.stack.remove(0).intValue();
        IntInsnNode cast = (IntInsnNode) insn;
        Object created;
        switch (cast.operand) {
            case T_BOOLEAN:
                created = new boolean[len];
                break;
            case T_CHAR:
                created = new char[len];
                break;
            case T_FLOAT:
                created = new float[len];
                break;
            case T_DOUBLE:
                created = new double[len];
                break;
            case T_BYTE:
                created = new byte[len];
                break;
            case T_SHORT:
                created = new short[len];
                break;
            case T_INT:
                created = new int[len];
                break;
            case T_LONG:
                created = new long[len];
                break;
            default:
                throw new ExecutionException("Unknown newarray type " + cast.operand);
        }
        f.stack.add(0, new JavaArray(created));
    }

    private static void newMultiArray(ExecutionFrame f, AbstractInsnNode insn) {
        MultiANewArrayInsnNode cast = (MultiANewArrayInsnNode) insn;
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < cast.dims; i++) {
            sizes.add(0, f.stack.remove(0).intValue());
        }
        Type type = Type.getType(cast.desc);
        Class<?> clazz = PrimitiveUtils.getPrimitiveByName(type.getClassName());
        Class<?> create = clazz == null ? Object.class : clazz;
        Object root = Array.newInstance(create, sizes.get(0));
        List<Object> currentArray = new ArrayList<>();
        currentArray.add(root);

        for (int i = 0; i < sizes.size() - 1; i++) {
            List<Object> temp = new ArrayList<>(currentArray);
            currentArray.clear();
            int size = sizes.get(i);
            for (int s = 0; s < size; s++) {
                for (Object o : temp) {
                    Object created = Array.newInstance(create, sizes.get(i + 1));
                    currentArray.add(created);
                    Array.set(o, s, created);
                }
            }
        }
        f.stack.add(0, new JavaArray(root));
    }
}
