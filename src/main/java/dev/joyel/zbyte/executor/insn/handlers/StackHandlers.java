package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaLong;
import dev.joyel.zbyte.executor.values.JavaValue;

import java.util.List;

public final class StackHandlers {
    private StackHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set((f, i) -> f.stack.remove(0), POP);
        dispatcher.set((f, i) -> {
            f.stack.remove(0);
            f.stack.remove(0);
        }, POP2);
        dispatcher.set((f, i) -> f.stack.add(0, f.stack.get(0)), DUP);
        dispatcher.set((f, i) -> {
            rejectWide(f.stack.get(0));
            f.stack.add(2, f.stack.get(0));
        }, DUP_X1);
        dispatcher.set((f, i) -> {
            JavaValue obj = f.stack.get(0);
            rejectWide(obj);
            f.stack.add(3, obj);
        }, DUP_X2);
        dispatcher.set((f, i) -> duplicatePair(f.stack, 2), DUP2);
        dispatcher.set((f, i) -> duplicatePair(f.stack, 3), DUP2_X1);
        dispatcher.set((f, i) -> duplicatePair(f.stack, 4), DUP2_X2);
        dispatcher.set((f, i) -> {
            JavaValue a = f.stack.remove(0);
            JavaValue b = f.stack.remove(0);
            f.stack.add(0, a);
            f.stack.add(0, b);
        }, SWAP);
    }

    private static void rejectWide(JavaValue value) {
        if (value instanceof JavaDouble || value instanceof JavaLong) {
            throw new ExecutionException("Dup with double/long");
        }
    }

    private static void duplicatePair(List<JavaValue> stack, int index) {
        JavaValue obj = stack.get(0);
        JavaValue obj1 = stack.get(1);
        stack.add(index, obj);
        stack.add(index + 1, obj1);
    }
}
