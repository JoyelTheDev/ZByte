package dev.joyel.zbyte.executor.insn;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

public final class ExceptionRouter {
    private ExceptionRouter() {
    }

    public static boolean route(ExecutionFrame frame, Throwable throwable) {
        MethodNode method = frame.method;
        if (method.tryCatchBlocks == null) {
            return false;
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            int position = method.instructions.indexOf(frame.now);
            if (method.instructions.indexOf(block.start) <= position && position < method.instructions.indexOf(block.end)) {
                if (block.type == null || block.type.equals("java/lang/Throwable") || isSubtype(frame.context, throwable, block.type)) {
                    frame.stack.clear();
                    frame.stack.add(JavaValue.valueOf(throwable));
                    frame.now = block.handler;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSubtype(Context context, Throwable throwable, String caught) {
        ClassNode node = context.dictionary.get(Type.getType(throwable.getClass()).getInternalName());
        while (node != null) {
            if (node.name.equals(caught)) {
                return true;
            }
            if (node.superName == null) {
                return false;
            }
            node = context.dictionary.get(node.superName);
        }
        return false;
    }
}
