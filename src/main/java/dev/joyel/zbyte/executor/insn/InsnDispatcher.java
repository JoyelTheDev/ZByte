package dev.joyel.zbyte.executor.insn;

import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.insn.handlers.ArithmeticHandlers;
import dev.joyel.zbyte.executor.insn.handlers.ArrayHandlers;
import dev.joyel.zbyte.executor.insn.handlers.BranchHandlers;
import dev.joyel.zbyte.executor.insn.handlers.ConstantHandlers;
import dev.joyel.zbyte.executor.insn.handlers.ConversionHandlers;
import dev.joyel.zbyte.executor.insn.handlers.FieldHandlers;
import dev.joyel.zbyte.executor.insn.handlers.InvokeHandlers;
import dev.joyel.zbyte.executor.insn.handlers.LocalHandlers;
import dev.joyel.zbyte.executor.insn.handlers.ObjectHandlers;
import dev.joyel.zbyte.executor.insn.handlers.ReturnHandlers;
import dev.joyel.zbyte.executor.insn.handlers.StackHandlers;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.Arrays;

public final class InsnDispatcher {
    private static final int SLOTS = 257;
    private static final InsnInterceptor[] NONE = new InsnInterceptor[0];

    private volatile InsnHandler[] handlers = new InsnHandler[SLOTS];
    private volatile InsnInterceptor[] global = NONE;
    private volatile InsnInterceptor[][] scoped = new InsnInterceptor[SLOTS][];

    public static InsnDispatcher empty() {
        InsnDispatcher dispatcher = new InsnDispatcher();
        dispatcher.set((frame, insn) -> {
        }, -1);
        return dispatcher;
    }

    public static InsnDispatcher withDefaults() {
        InsnDispatcher dispatcher = empty();
        ConstantHandlers.register(dispatcher);
        LocalHandlers.register(dispatcher);
        ArrayHandlers.register(dispatcher);
        StackHandlers.register(dispatcher);
        ArithmeticHandlers.register(dispatcher);
        ConversionHandlers.register(dispatcher);
        BranchHandlers.register(dispatcher);
        ReturnHandlers.register(dispatcher);
        FieldHandlers.register(dispatcher);
        InvokeHandlers.register(dispatcher);
        ObjectHandlers.register(dispatcher);
        return dispatcher;
    }

    private static int slot(int opcode) {
        return opcode + 1;
    }

    public synchronized InsnDispatcher set(int opcode, InsnHandler handler) {
        InsnHandler[] copy = handlers.clone();
        copy[slot(opcode)] = handler;
        handlers = copy;
        return this;
    }

    public synchronized InsnDispatcher set(InsnHandler handler, int... opcodes) {
        InsnHandler[] copy = handlers.clone();
        for (int opcode : opcodes) {
            copy[slot(opcode)] = handler;
        }
        handlers = copy;
        return this;
    }

    public InsnHandler handlerFor(int opcode) {
        return handlers[slot(opcode)];
    }

    public synchronized InsnDispatcher intercept(InsnInterceptor interceptor) {
        InsnInterceptor[] copy = Arrays.copyOf(global, global.length + 1);
        copy[global.length] = interceptor;
        global = copy;
        return this;
    }

    public synchronized InsnDispatcher intercept(int opcode, InsnInterceptor interceptor) {
        InsnInterceptor[][] copy = scoped.clone();
        InsnInterceptor[] current = copy[slot(opcode)];
        int size = current == null ? 0 : current.length;
        InsnInterceptor[] extended = current == null ? new InsnInterceptor[1] : Arrays.copyOf(current, size + 1);
        extended[size] = interceptor;
        copy[slot(opcode)] = extended;
        scoped = copy;
        return this;
    }

    public synchronized InsnDispatcher intercept(InsnInterceptor interceptor, int... opcodes) {
        for (int opcode : opcodes) {
            intercept(opcode, interceptor);
        }
        return this;
    }

    public synchronized InsnDispatcher remove(InsnInterceptor interceptor) {
        global = without(global, interceptor);
        InsnInterceptor[][] copy = scoped.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] != null) {
                InsnInterceptor[] reduced = without(copy[i], interceptor);
                copy[i] = reduced.length == 0 ? null : reduced;
            }
        }
        scoped = copy;
        return this;
    }

    private static InsnInterceptor[] without(InsnInterceptor[] source, InsnInterceptor target) {
        return Arrays.stream(source).filter(candidate -> candidate != target).toArray(InsnInterceptor[]::new);
    }

    public void dispatch(ExecutionFrame frame) throws Throwable {
        AbstractInsnNode insn = frame.now;
        int slot = slot(insn.getOpcode());
        InsnInterceptor[] all = global;
        InsnInterceptor[] mine = scoped[slot];
        boolean handled = false;
        for (InsnInterceptor interceptor : all) {
            if (interceptor.before(frame, insn) == InterceptResult.HANDLED) {
                handled = true;
                break;
            }
        }
        if (!handled && mine != null) {
            for (InsnInterceptor interceptor : mine) {
                if (interceptor.before(frame, insn) == InterceptResult.HANDLED) {
                    handled = true;
                    break;
                }
            }
        }
        if (!handled) {
            InsnHandler handler = handlers[slot];
            if (handler == null) {
                throw new ExecutionException("Unknown opcode " + insn.getClass().getSimpleName() + " " + insn.getOpcode() + " ");
            }
            handler.handle(frame, insn);
        }
        for (InsnInterceptor interceptor : all) {
            interceptor.after(frame, insn);
        }
        if (mine != null) {
            for (InsnInterceptor interceptor : mine) {
                interceptor.after(frame, insn);
            }
        }
    }
}
