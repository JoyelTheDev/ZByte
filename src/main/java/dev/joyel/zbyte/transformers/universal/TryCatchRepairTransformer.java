package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class TryCatchRepairTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        boolean changed = false;
        int removed = 0;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                if (methodNode.tryCatchBlocks == null || methodNode.tryCatchBlocks.isEmpty()) {
                    continue;
                }
                Set<AbstractInsnNode> present = Collections.newSetFromMap(new IdentityHashMap<>());
                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    present.add(insn);
                }
                int before = methodNode.tryCatchBlocks.size();
                methodNode.tryCatchBlocks.removeIf(block -> isBroken(methodNode, present, block));
                int diff = before - methodNode.tryCatchBlocks.size();
                removed += diff;
                changed |= diff > 0;
            }
        }
        if (removed > 0) {
            logger.info("Removed {} broken try-catch blocks", removed);
        }
        return changed;
    }

    private static boolean isBroken(MethodNode methodNode, Set<AbstractInsnNode> present, TryCatchBlockNode block) {
        LabelNode start = block.start;
        LabelNode end = block.end;
        LabelNode handler = block.handler;
        if (start == null || end == null || handler == null) {
            return true;
        }
        if (!present.contains(start) || !present.contains(end) || !present.contains(handler)) {
            return true;
        }
        return methodNode.instructions.indexOf(start) >= methodNode.instructions.indexOf(end);
    }
}
