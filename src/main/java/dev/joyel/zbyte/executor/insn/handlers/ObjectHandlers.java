package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.exceptions.NoSuchComparisonHandlerException;
import dev.joyel.zbyte.executor.insn.ExecutionFrame;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaObject;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class ObjectHandlers {
    private ObjectHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set((f, i) -> f.stack.add(0, new JavaObject(((TypeInsnNode) i).desc)), NEW);
        dispatcher.set(ObjectHandlers::athrow, ATHROW);
        dispatcher.set(ObjectHandlers::checkcast, CHECKCAST);
        dispatcher.set(ObjectHandlers::instanceOf, INSTANCEOF);
        dispatcher.set((f, i) -> f.context.monitor.enter(f.stack.remove(0)), MONITORENTER);
        dispatcher.set((f, i) -> f.context.monitor.exit(f.stack.remove(0)), MONITOREXIT);
    }

    private static Type typeOf(String desc) {
        try {
            return Type.getType(desc);
        } catch (Throwable ignored) {
            return Type.getObjectType(desc);
        }
    }

    private static void athrow(ExecutionFrame f, AbstractInsnNode insn) {
        Object throwable = f.stack.remove(0).value();
        if (throwable instanceof Throwable) {
            f.raise((Throwable) throwable);
        } else if (throwable == null) {
            NullPointerException exception = new NullPointerException();
            exception.setStackTrace(f.context.getStackTrace());
            f.raise(exception);
        } else {
            throw new ExecutionException("Expected a throwable on stack");
        }
    }

    private static void checkcast(ExecutionFrame f, AbstractInsnNode insn) {
        TypeInsnNode cast = (TypeInsnNode) insn;
        JavaValue obj = f.stack.get(0);
        Type type = typeOf(cast.desc);
        if (obj.value() != null) {
            if (f.context.provider.canCheckcast(obj, type, f.context)) {
                if (!f.context.provider.checkcast(obj, type, f.context)) {
                    throw new ClassCastException(cast.desc);
                }
            } else {
                throw new NoSuchComparisonHandlerException("No comparator found for " + cast.desc);
            }
        }
    }

    private static void instanceOf(ExecutionFrame f, AbstractInsnNode insn) {
        TypeInsnNode cast = (TypeInsnNode) insn;
        JavaValue obj = f.stack.remove(0);
        Type type = typeOf(cast.desc);
        if (f.context.provider.canCheckInstanceOf(obj, type, f.context)) {
            boolean is = f.context.provider.instanceOf(obj, type, f.context);
            f.stack.add(0, new JavaInteger(is ? 1 : 0));
        } else {
            throw new NoSuchComparisonHandlerException("No comparator found for " + cast.desc);
        }
    }
}
