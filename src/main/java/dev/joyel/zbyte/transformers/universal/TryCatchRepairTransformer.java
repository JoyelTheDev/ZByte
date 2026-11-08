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
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                if (methodNode.tryCatchBlocks == null || methodNode.tryCatchBlocks.isEmpty()) {
                    continue;
                }
                Set<AbstractInsnNode> present = Collections.newSetFromMap(new IdentityHashMap<>());
                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    present.add(insn);
                }
                changed |= methodNode.tryCatchBlocks.removeIf(block -> isBroken(methodNode, present, block));
            }
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
