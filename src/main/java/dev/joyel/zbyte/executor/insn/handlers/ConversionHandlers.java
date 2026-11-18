package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaByte;
import dev.joyel.zbyte.executor.values.JavaCharacter;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaFloat;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaLong;
import dev.joyel.zbyte.executor.values.JavaShort;
import dev.joyel.zbyte.executor.values.JavaTop;
import dev.joyel.zbyte.executor.values.JavaValue;

import java.util.List;

public final class ConversionHandlers {
    private static final boolean VERIFY = MethodExecutor.VERIFY;

    private ConversionHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set((f, i) -> {
            f.stack.add(0, new JavaLong(f.stack.remove(0).intValue()));
            f.stack.add(0, new JavaTop());
        }, I2L);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaFloat(f.stack.remove(0).intValue())), I2F);
        dispatcher.set((f, i) -> {
            f.stack.add(0, new JavaDouble(f.stack.remove(0).intValue()));
            f.stack.add(0, new JavaTop());
        }, I2D);
        dispatcher.set((f, i) -> {
            discardTop(f.stack);
            f.stack.add(0, new JavaInteger((int) f.stack.remove(0).longValue()));
        }, L2I);
        dispatcher.set((f, i) -> {
            discardTop(f.stack);
            f.stack.add(0, new JavaFloat((float) f.stack.remove(0).longValue()));
        }, L2F);
        dispatcher.set((f, i) -> f.stack.add(1, new JavaDouble(f.stack.remove(1).longValue())), L2D);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaInteger((int) f.stack.remove(0).floatValue())), F2I);
        dispatcher.set((f, i) -> {
            f.stack.add(0, new JavaLong((long) f.stack.remove(0).floatValue()));
            f.stack.add(0, new JavaTop());
        }, F2L);
        dispatcher.set((f, i) -> {
            f.stack.add(0, new JavaDouble((double) f.stack.remove(0).floatValue()));
            f.stack.add(0, new JavaTop());
        }, F2D);
        dispatcher.set((f, i) -> {
            discardTop(f.stack);
            f.stack.add(0, new JavaInteger((int) f.stack.remove(0).doubleValue()));
        }, D2I);
        dispatcher.set((f, i) -> f.stack.add(1, new JavaLong((long) f.stack.remove(1).doubleValue())), D2L);
        dispatcher.set((f, i) -> {
            discardTop(f.stack);
            f.stack.add(0, new JavaFloat((float) f.stack.remove(0).doubleValue()));
        }, D2F);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaByte((byte) f.stack.remove(0).intValue())), I2B);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaCharacter((char) f.stack.remove(0).intValue())), I2C);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaShort((short) f.stack.remove(0).intValue())), I2S);
    }

    private static void discardTop(List<JavaValue> stack) {
        JavaValue value = stack.remove(0);
        if (VERIFY && !(value instanceof JavaTop)) {
            throw new ExecutionException("Expected JavaTop");
        }
    }
}
