package dev.joyel.zbyte.executor.insn;

import dev.joyel.zbyte.executor.exceptions.NoSuchMethodHandlerException;
import dev.joyel.zbyte.executor.values.*;
import dev.xdark.ssvm.VirtualMachine;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.objectweb.asm.Opcodes.*;

public final class SsvmFallbackInterceptor implements InsnInterceptor {

    private final VirtualMachine vm;

    public SsvmFallbackInterceptor(VirtualMachine vm) {
        this.vm = vm;
    }

    @Override
    public InterceptResult before(ExecutionFrame frame, AbstractInsnNode insn) throws Throwable {
        int op = insn.getOpcode();
        if (op != INVOKEVIRTUAL && op != INVOKESTATIC && op != INVOKEINTERFACE && op != INVOKESPECIAL) {
            return InterceptResult.PROCEED;
        }
        MethodInsnNode m = (MethodInsnNode) insn;
        try {
            frame.context.dispatcher.handlerFor(op).handle(frame, insn);
            return InterceptResult.HANDLED;
        } catch (NoSuchMethodHandlerException e) {
            return tryViaSsvm(frame, m) ? InterceptResult.HANDLED : InterceptResult.PROCEED;
        }
    }

    private boolean tryViaSsvm(ExecutionFrame frame, MethodInsnNode m) {
        try {
            var cls = vm.findBootstrapClass(m.owner);
            if (cls == null) return false;
            // delegate to SSVM execution
            // ... (same arg mapping as Pattern 1)
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}