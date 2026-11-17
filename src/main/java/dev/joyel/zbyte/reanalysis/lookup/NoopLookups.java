package dev.joyel.zbyte.reanalysis.lookup;

import dev.joyel.zbyte.reanalysis.value.ReValue;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;

public final class NoopLookups {
    public static final GetStaticLookup GET_STATIC = new GetStaticLookup() {
        @Override
        public ReValue get(FieldInsnNode field) {
            return null;
        }

        @Override
        public boolean hasLookup(FieldInsnNode field) {
            return false;
        }
    };

    public static final InvokeStaticLookup INVOKE_STATIC = new InvokeStaticLookup() {
        @Override
        public ReValue get(MethodInsnNode method, List<? extends ReValue> values) {
            return null;
        }

        @Override
        public boolean hasLookup(MethodInsnNode method) {
            return false;
        }
    };

    public static final InvokeVirtualLookup INVOKE_VIRTUAL = new InvokeVirtualLookup() {
        @Override
        public ReValue get(MethodInsnNode method, ReValue context, List<? extends ReValue> values) {
            return null;
        }

        @Override
        public boolean hasLookup(MethodInsnNode method) {
            return false;
        }
    };

    private NoopLookups() {
    }
}
