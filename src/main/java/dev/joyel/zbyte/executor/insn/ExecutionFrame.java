package dev.joyel.zbyte.executor.insn;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public final class ExecutionFrame {
    public final ClassNode classNode;
    public final MethodNode method;
    public final List<JavaValue> stack;
    public final List<JavaValue> locals;
    public final Context context;

    public AbstractInsnNode now;
    public Throwable toThrow;
    public Object result;
    public boolean returned;

    public ExecutionFrame(ClassNode classNode, MethodNode method, AbstractInsnNode now, List<JavaValue> stack, List<JavaValue> locals, Context context) {
        this.classNode = classNode;
        this.method = method;
        this.now = now;
        this.stack = stack;
        this.locals = locals;
        this.context = context;
    }

    public void jump(AbstractInsnNode target) {
        now = target;
    }

    public void returnWith(Object value) {
        result = value;
        returned = true;
    }

    public void raise(Throwable throwable) {
        toThrow = throwable;
    }
}
