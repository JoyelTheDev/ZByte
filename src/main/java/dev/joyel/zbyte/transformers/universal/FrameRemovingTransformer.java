package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.*;

import org.objectweb.asm.tree.*;

public class FrameRemovingTransformer extends Transformer<TransformerConfig> {
    private boolean changed;

    private void markChange() {
        changed = true;
    }

    @Override
    public boolean transform() throws Throwable {
        changed = false;
        run();
        return changed;
    }

    private void run() throws Exception {
        for (ClassNode classNode : new ArrayList<>(classNodes())) {
            for (MethodNode method : classNode.methods) {
                InsnList instructions = method.instructions;
                if (instructions == null) continue;
                for (int i = instructions.size() - 1; i >= 0; i--) {
                    AbstractInsnNode insn = instructions.get(i);
                    if (insn instanceof FrameNode) {
                        instructions.remove(insn);
                        markChange();
                    }
                }
            }
        }
    }
}
