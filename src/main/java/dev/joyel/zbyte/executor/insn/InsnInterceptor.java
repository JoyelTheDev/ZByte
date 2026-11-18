package dev.joyel.zbyte.executor.insn;

import org.objectweb.asm.tree.AbstractInsnNode;

@FunctionalInterface
public interface InsnInterceptor {
    InterceptResult before(ExecutionFrame frame, AbstractInsnNode insn) throws Throwable;

    default void after(ExecutionFrame frame, AbstractInsnNode insn) throws Throwable {
    }
}
