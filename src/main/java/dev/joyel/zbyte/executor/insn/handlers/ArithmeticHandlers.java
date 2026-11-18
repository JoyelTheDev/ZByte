package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaFloat;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaLong;

import java.util.function.BiFunction;

public final class ArithmeticHandlers {
    private ArithmeticHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        integer(dispatcher, IADD, (x, y) -> x + y);
        integer(dispatcher, ISUB, (x, y) -> x - y);
        integer(dispatcher, IMUL, (x, y) -> x * y);
        integer(dispatcher, IDIV, (x, y) -> x / y);
        integer(dispatcher, IREM, (x, y) -> x % y);
        integer(dispatcher, ISHL, (x, y) -> x << y);
        integer(dispatcher, ISHR, (x, y) -> x >> y);
        integer(dispatcher, IUSHR, (x, y) -> x >>> y);
        integer(dispatcher, IAND, (x, y) -> x & y);
        integer(dispatcher, IOR, (x, y) -> x | y);
        integer(dispatcher, IXOR, (x, y) -> x ^ y);

        longs(dispatcher, LADD, (x, y) -> x + y);
        longs(dispatcher, LSUB, (x, y) -> x - y);
        longs(dispatcher, LMUL, (x, y) -> x * y);
        longs(dispatcher, LDIV, (x, y) -> x / y);
        longs(dispatcher, LREM, (x, y) -> x % y);
        longs(dispatcher, LAND, (x, y) -> x & y);
        longs(dispatcher, LOR, (x, y) -> x | y);
        longs(dispatcher, LXOR, (x, y) -> x ^ y);
        dispatcher.set((f, i) -> StackOps.longShift(f.stack, (x, y) -> x << y), LSHL);
        dispatcher.set((f, i) -> StackOps.longShift(f.stack, (x, y) -> x >> y), LSHR);
        dispatcher.set((f, i) -> StackOps.longShift(f.stack, (x, y) -> x >>> y), LUSHR);
        dispatcher.set((f, i) -> StackOps.longCompare(f.stack, (x, y) -> x.compareTo(y)), LCMP);

        floats(dispatcher, FADD, (x, y) -> x + y);
        floats(dispatcher, FSUB, (x, y) -> x - y);
        floats(dispatcher, FMUL, (x, y) -> x * y);
        floats(dispatcher, FDIV, (x, y) -> x / y);
        floats(dispatcher, FREM, (x, y) -> x % y);
        dispatcher.set((f, i) -> StackOps.floatCompare(f.stack, (x, y) -> Float.isNaN(x) || Float.isNaN(y) ? -1 : x.compareTo(y)), FCMPL);
        dispatcher.set((f, i) -> StackOps.floatCompare(f.stack, (x, y) -> Float.isNaN(x) || Float.isNaN(y) ? 1 : x.compareTo(y)), FCMPG);

        doubles(dispatcher, DADD, (x, y) -> x + y);
        doubles(dispatcher, DSUB, (x, y) -> x - y);
        doubles(dispatcher, DMUL, (x, y) -> x * y);
        doubles(dispatcher, DDIV, (x, y) -> x / y);
        doubles(dispatcher, DREM, (x, y) -> x % y);
        dispatcher.set((f, i) -> StackOps.doubleCompare(f.stack, (x, y) -> Double.isNaN(x) || Double.isNaN(y) ? -1 : x.compareTo(y)), DCMPL);
        dispatcher.set((f, i) -> StackOps.doubleCompare(f.stack, (x, y) -> Double.isNaN(x) || Double.isNaN(y) ? 1 : x.compareTo(y)), DCMPG);

        dispatcher.set((f, i) -> f.stack.set(0, new JavaInteger(-f.stack.get(0).intValue())), INEG);
        dispatcher.set((f, i) -> f.stack.set(1, new JavaLong(-f.stack.get(1).longValue())), LNEG);
        dispatcher.set((f, i) -> f.stack.set(0, new JavaFloat(-f.stack.get(0).floatValue())), FNEG);
        dispatcher.set((f, i) -> f.stack.set(1, new JavaDouble(-f.stack.get(1).doubleValue())), DNEG);
    }

    private static void integer(InsnDispatcher dispatcher, int opcode, BiFunction<Integer, Integer, Integer> operation) {
        dispatcher.set((f, i) -> StackOps.integerMath(f.stack, operation), opcode);
    }

    private static void longs(InsnDispatcher dispatcher, int opcode, BiFunction<Long, Long, Long> operation) {
        dispatcher.set((f, i) -> StackOps.longMath(f.stack, operation), opcode);
    }

    private static void floats(InsnDispatcher dispatcher, int opcode, BiFunction<Float, Float, Float> operation) {
        dispatcher.set((f, i) -> StackOps.floatMath(f.stack, operation), opcode);
    }

    private static void doubles(InsnDispatcher dispatcher, int opcode, BiFunction<Double, Double, Double> operation) {
        dispatcher.set((f, i) -> StackOps.doubleMath(f.stack, operation), opcode);
    }
}
