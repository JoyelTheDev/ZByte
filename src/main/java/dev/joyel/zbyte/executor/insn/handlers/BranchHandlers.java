package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.exceptions.NoSuchMethodHandlerException;
import dev.joyel.zbyte.executor.insn.ExecutionFrame;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.insn.InsnHandler;
import dev.joyel.zbyte.executor.values.JavaAddress;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.function.IntPredicate;

public final class BranchHandlers {
    private BranchHandlers() {
    }

    @FunctionalInterface
    private interface IntComparison {
        boolean test(int left, int right);
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set(compareToZero(v -> v == 0), IFEQ);
        dispatcher.set(compareToZero(v -> v != 0), IFNE);
        dispatcher.set(compareToZero(v -> v < 0), IFLT);
        dispatcher.set(compareToZero(v -> v >= 0), IFGE);
        dispatcher.set(compareToZero(v -> v > 0), IFGT);
        dispatcher.set(compareToZero(v -> v <= 0), IFLE);

        dispatcher.set(compareInts((left, right) -> left == right), IF_ICMPEQ);
        dispatcher.set(compareInts((left, right) -> left != right), IF_ICMPNE);
        dispatcher.set(compareInts((left, right) -> left < right), IF_ICMPLT);
        dispatcher.set(compareInts((left, right) -> left >= right), IF_ICMPGE);
        dispatcher.set(compareInts((left, right) -> left > right), IF_ICMPGT);
        dispatcher.set(compareInts((left, right) -> left <= right), IF_ICMPLE);

        dispatcher.set(compareReferences(false), IF_ACMPNE);
        dispatcher.set(compareReferences(true), IF_ACMPEQ);

        dispatcher.set((f, i) -> f.jump(((JumpInsnNode) i).label), GOTO);
        dispatcher.set((f, i) -> {
            JumpInsnNode cast = (JumpInsnNode) i;
            f.stack.add(0, new JavaAddress(i));
            f.jump(cast.label);
        }, JSR);
        dispatcher.set((f, i) -> {
            JavaValue value = f.locals.get(((VarInsnNode) i).var);
            if (!(value instanceof JavaAddress)) {
                throw new ExecutionException("Expected address on stack");
            }
            f.jump((AbstractInsnNode) ((JavaAddress) value).value());
        }, RET);
        dispatcher.set(BranchHandlers::tableSwitch, TABLESWITCH);
        dispatcher.set(BranchHandlers::lookupSwitch, LOOKUPSWITCH);

        dispatcher.set((f, i) -> {
            JumpInsnNode cast = (JumpInsnNode) i;
            JavaValue obj = f.stack.remove(0);
            if (obj.value() == null) {
                f.jump(cast.label);
            }
        }, IFNULL);
        dispatcher.set((f, i) -> {
            JumpInsnNode cast = (JumpInsnNode) i;
            JavaValue obj = f.stack.remove(0);
            if (obj.value() != null) {
                f.jump(cast.label);
            }
        }, IFNONNULL);
    }

    private static InsnHandler compareToZero(IntPredicate test) {
        return (f, i) -> {
            JumpInsnNode cast = (JumpInsnNode) i;
            JavaValue o = f.stack.remove(0);
            if (test.test(o.intValue())) {
                f.jump(cast.label);
            }
        };
    }

    private static InsnHandler compareInts(IntComparison test) {
        return (f, i) -> {
            JumpInsnNode cast = (JumpInsnNode) i;
            JavaValue right = f.stack.remove(0);
            JavaValue left = f.stack.remove(0);
            if (test.test(left.intValue(), right.intValue())) {
                f.jump(cast.label);
            }
        };
    }

    private static InsnHandler compareReferences(boolean jumpWhenEqual) {
        return (f, i) -> {
            JumpInsnNode cast = (JumpInsnNode) i;
            JavaValue o = f.stack.remove(0);
            JavaValue o1 = f.stack.remove(0);
            Context context = f.context;
            if (context.provider.canCheckEquality(o, o1, context)) {
                boolean eq = context.provider.checkEquality(o, o1, context);
                if (eq == jumpWhenEqual) {
                    f.jump(cast.label);
                }
            } else {
                throw new NoSuchMethodHandlerException("Could not find comparison for " + o.type() + " " + o1.type());
            }
        };
    }

    private static void tableSwitch(ExecutionFrame f, AbstractInsnNode insn) {
        int x = f.stack.remove(0).intValue();
        TableSwitchInsnNode cast = (TableSwitchInsnNode) insn;
        int offset = cast.min;
        if (x < cast.labels.size() + offset && x - offset >= 0) {
            f.jump(cast.labels.get(x - offset));
        } else {
            f.jump(cast.dflt);
        }
    }

    private static void lookupSwitch(ExecutionFrame f, AbstractInsnNode insn) {
        Integer x = f.stack.remove(0).intValue();
        LookupSwitchInsnNode cast = (LookupSwitchInsnNode) insn;
        if (cast.keys.indexOf(x) != -1) {
            f.jump(cast.labels.get(cast.keys.indexOf(x)));
        } else {
            f.jump(cast.dflt);
        }
    }
}
