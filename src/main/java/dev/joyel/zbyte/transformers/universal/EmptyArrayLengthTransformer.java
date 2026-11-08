package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

public class EmptyArrayLengthTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                Set<LabelNode> referenced = UniversalSupport.referencedLabels(methodNode);
                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    if (insn.getOpcode() != ARRAYLENGTH) {
                        continue;
                    }
                    AbstractInsnNode creation = UniversalSupport.previous(insn, referenced);
                    if (creation == null || (creation.getOpcode() != NEWARRAY && creation.getOpcode() != ANEWARRAY)) {
                        continue;
                    }
                    AbstractInsnNode size = UniversalSupport.previous(creation, referenced);
                    Integer length = UniversalSupport.intConstant(size);
                    if (length == null || length < 0) {
                        continue;
                    }
                    methodNode.instructions.set(insn, UniversalSupport.constantInsn(length));
                    methodNode.instructions.remove(creation);
                    methodNode.instructions.remove(size);
                    changed = true;
                }
            }
        }
        return changed;
    }
}
