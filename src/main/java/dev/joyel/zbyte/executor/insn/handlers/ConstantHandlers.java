package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.defined.types.JavaClass;
import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.insn.ExecutionFrame;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaByte;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaFloat;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaLong;
import dev.joyel.zbyte.executor.values.JavaObject;
import dev.joyel.zbyte.executor.values.JavaShort;
import dev.joyel.zbyte.executor.values.JavaTop;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

public final class ConstantHandlers {
    private ConstantHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set((f, i) -> {
        }, NOP);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaObject(null, "java/lang/Object")), ACONST_NULL);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaInteger(i.getOpcode() - 3)),
                ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5);
        dispatcher.set((f, i) -> {
            f.stack.add(0, new JavaLong(i.getOpcode() - 9));
            f.stack.add(0, new JavaTop());
        }, LCONST_0, LCONST_1);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaFloat(i.getOpcode() - 11)), FCONST_0, FCONST_1, FCONST_2);
        dispatcher.set((f, i) -> {
            f.stack.add(0, new JavaDouble(i.getOpcode() - 14));
            f.stack.add(0, new JavaTop());
        }, DCONST_0, DCONST_1);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaByte((byte) ((IntInsnNode) i).operand)), BIPUSH);
        dispatcher.set((f, i) -> f.stack.add(0, new JavaShort((short) ((IntInsnNode) i).operand)), SIPUSH);
        dispatcher.set(ConstantHandlers::ldc, LDC);
    }

    private static void ldc(ExecutionFrame f, AbstractInsnNode insn) {
        Object load = ((LdcInsnNode) insn).cst;
        if (load instanceof Type) {
            Type type = (Type) load;
            load = new JavaClass(type.getInternalName().replace('/', '.'), f.context);
        }
        if (load instanceof Integer) {
            f.stack.add(0, new JavaInteger((Integer) load));
        } else if (load instanceof Float) {
            f.stack.add(0, new JavaFloat((Float) load));
        } else if (load instanceof Double) {
            f.stack.add(0, new JavaDouble((Double) load));
            f.stack.add(0, new JavaTop());
        } else if (load instanceof Long) {
            f.stack.add(0, new JavaLong((Long) load));
            f.stack.add(0, new JavaTop());
        } else if (load instanceof String) {
            f.stack.add(0, new JavaObject(load, "java/lang/String"));
        } else if (load instanceof JavaClass) {
            f.stack.add(0, new JavaObject(load, "java/lang/Class"));
        } else {
            throw new ExecutionException("Unexpected ldc type " + (load == null ? "null" : load.getClass()));
        }
    }
}
