package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.insn.ExecutionFrame;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaFloat;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaLong;
import dev.joyel.zbyte.executor.values.JavaObject;
import dev.joyel.zbyte.executor.values.JavaTop;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class LocalHandlers {
    private static final boolean VERIFY = MethodExecutor.VERIFY;

    private LocalHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set((f, i) -> {
            loadNarrow(f, i);
            if (VERIFY && !(f.stack.get(0) instanceof JavaInteger)) {
                throw new ExecutionException("Expected Integer");
            }
        }, ILOAD);
        dispatcher.set((f, i) -> {
            loadNarrow(f, i);
            if (VERIFY && !(f.stack.get(0) instanceof JavaFloat)) {
                throw new ExecutionException("Expected Float");
            }
        }, FLOAD);
        dispatcher.set((f, i) -> {
            loadNarrow(f, i);
            if (VERIFY && !(f.stack.get(0) instanceof JavaObject)) {
                throw new ExecutionException("Expected Object");
            }
        }, ALOAD);
        dispatcher.set((f, i) -> {
            loadWide(f, i);
            if (VERIFY && !(f.stack.get(1) instanceof JavaLong)) {
                throw new ExecutionException("Expected Long");
            }
        }, LLOAD);
        dispatcher.set((f, i) -> {
            loadWide(f, i);
            if (VERIFY && !(f.stack.get(1) instanceof JavaDouble)) {
                throw new ExecutionException("Expected Double");
            }
        }, DLOAD);
        dispatcher.set((f, i) -> f.locals.set(((VarInsnNode) i).var, f.stack.remove(0).copy()), ISTORE, FSTORE, ASTORE);
        dispatcher.set((f, i) -> {
            f.stack.remove(0);
            f.locals.set(((VarInsnNode) i).var, f.stack.remove(0).copy());
        }, LSTORE, DSTORE);
        dispatcher.set((f, i) -> {
            IincInsnNode cast = (IincInsnNode) i;
            JavaInteger integer = (JavaInteger) f.locals.get(cast.var);
            integer.increment(cast.incr);
        }, IINC);
    }

    private static void loadNarrow(ExecutionFrame f, AbstractInsnNode insn) {
        f.stack.add(0, f.locals.get(((VarInsnNode) insn).var).copy());
    }

    private static void loadWide(ExecutionFrame f, AbstractInsnNode insn) {
        f.stack.add(0, f.locals.get(((VarInsnNode) insn).var).copy());
        f.stack.add(0, new JavaTop());
    }
}
