package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.analysis.value.ReValue;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;

public interface InvokeDynamicResolver {

    default int getPriority() {
        return 0;
    }

    default void setup(IndyContext context) {
    }

    ResolvedInvokeDynamic resolve(IndyContext context,
                                  ClassNode classNode,
                                  MethodNode method,
                                  InvokeDynamicInsnNode instruction,
                                  Frame<ReValue> frame);

    default ResolvedInvokeDynamic resolveWithoutFrame(IndyContext context,
                                                      ClassNode classNode,
                                                      MethodNode method,
                                                      InvokeDynamicInsnNode instruction) {
        return null;
    }

    record ResolvedInvokeDynamic(Handle target, int ignoredTrailingArgumentCount) {
        public ResolvedInvokeDynamic {
            if (ignoredTrailingArgumentCount < 0) {
                throw new IllegalArgumentException("Must provide non-negative trailing arg count");
            }
        }
    }
}
