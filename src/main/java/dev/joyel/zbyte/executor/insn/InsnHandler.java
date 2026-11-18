package dev.joyel.zbyte.executor.insn;

import org.objectweb.asm.tree.AbstractInsnNode;

@FunctionalInterface
public interface InsnHandler {
    void handle(ExecutionFrame frame, AbstractInsnNode insn) throws Throwable;
}
