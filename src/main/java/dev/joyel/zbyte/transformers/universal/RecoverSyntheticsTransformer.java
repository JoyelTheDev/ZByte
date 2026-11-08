package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class RecoverSyntheticsTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                if ((methodNode.access & ACC_SYNTHETIC) != 0) {
                    continue;
                }
                if (methodNode.name.startsWith("lambda$") || methodNode.name.matches("access\\$\\d+")) {
                    methodNode.access |= ACC_SYNTHETIC;
                    changed = true;
                }
            }
        }
        return changed;
    }
}
