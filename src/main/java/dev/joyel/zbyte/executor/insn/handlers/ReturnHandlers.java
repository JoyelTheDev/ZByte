package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import com.google.common.base.Optional;
import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaObject;
import dev.joyel.zbyte.executor.values.JavaValue;
import dev.joyel.zbyte.utils.TypeStore;
import org.objectweb.asm.Type;

public final class ReturnHandlers {
    private ReturnHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set((f, i) -> {
            f.context.pop();
            f.returnWith(MethodExecutor.convert(MethodExecutor.value(f.stack.remove(0)), Type.getReturnType(f.method.desc).getDescriptor()));
        }, IRETURN);
        dispatcher.set((f, i) -> {
            f.context.pop();
            f.stack.remove(0);
            f.returnWith((Long) f.stack.remove(0).longValue());
        }, LRETURN);
        dispatcher.set((f, i) -> {
            f.context.pop();
            f.returnWith((Float) f.stack.remove(0).floatValue());
        }, FRETURN);
        dispatcher.set((f, i) -> {
            f.context.pop();
            f.stack.remove(0);
            f.returnWith((Double) f.stack.remove(0).doubleValue());
        }, DRETURN);
        dispatcher.set((f, i) -> {
            f.context.pop();
            JavaValue value = f.stack.remove(0);
            TypeStore.returnObjects.put(Thread.currentThread().getId(), (JavaObject) value);
            f.returnWith(value.value());
        }, ARETURN);
        dispatcher.set((f, i) -> {
            f.context.pop();
            f.returnWith(Optional.absent());
        }, RETURN);
    }
}
