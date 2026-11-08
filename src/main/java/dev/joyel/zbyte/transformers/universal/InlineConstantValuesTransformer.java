package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;

@TransformerConfig.ConfigOptions(configClass = InlineConstantValuesTransformer.Config.class)
public class InlineConstantValuesTransformer extends Transformer<InlineConstantValuesTransformer.Config> {

    @Override
    public boolean transform() throws Throwable {
        int limit = getConfig().getMaxAnalysisInstructions();
        int folded = 0;
        int skipped = 0;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : new ArrayList<>(classNode.methods)) {
                if (limit > 0 && methodNode.instructions.size() > limit) {
                    skipped++;
                    continue;
                }
                folded += process(classNode, methodNode);
            }
        }
        if (skipped > 0) {
            logger.info("Skipped constant analysis for {} methods above {} instructions", skipped, limit);
        }
        if (folded > 0) {
            logger.info("Folded {} constant expressions", folded);
        }
        return folded > 0;
    }

    private int process(ClassNode classNode, MethodNode methodNode) {
        if (!containsFoldable(methodNode)) {
            return 0;
        }
        Frame<ConstantAnalysis.Value>[] frames = ConstantAnalysis.analyze(classNode, methodNode);
        if (frames == null) {
            return 0;
        }
        AbstractInsnNode[] insns = methodNode.instructions.toArray();
        int folded = 0;
        for (int i = 0; i < insns.length; i++) {
            Frame<ConstantAnalysis.Value> frame = frames[i];
            if (frame == null) {
                continue;
            }
            AbstractInsnNode insn = insns[i];
            int opcode = insn.getOpcode();
            boolean unary = ConstantAnalysis.isFoldableUnary(opcode);
            boolean binary = ConstantAnalysis.isFoldableBinary(opcode);
            if (!unary && !binary) {
                continue;
            }
            int top = frame.getStackSize() - 1;
            ConstantAnalysis.Value first = frame.getStack(top);
            ConstantAnalysis.Value second = binary ? frame.getStack(top - 1) : null;
            if (!first.isConstant() || (binary && !second.isConstant())) {
                continue;
            }
            Object result = binary
                    ? ConstantAnalysis.fold(opcode, second.getConstant(), first.getConstant())
                    : ConstantAnalysis.fold(opcode, first.getConstant(), null);
            if (result == null) {
                continue;
            }
            methodNode.instructions.insertBefore(insn, new InsnNode(first.getSize() == 2 ? POP2 : POP));
            if (binary) {
                methodNode.instructions.insertBefore(insn, new InsnNode(second.getSize() == 2 ? POP2 : POP));
            }
            methodNode.instructions.set(insn, UniversalSupport.constantInsn(result));
            folded++;
        }
        return folded;
    }

    private static boolean containsFoldable(MethodNode methodNode) {
        for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
            int opcode = insn.getOpcode();
            if (ConstantAnalysis.isFoldableUnary(opcode) || ConstantAnalysis.isFoldableBinary(opcode)) {
                return true;
            }
        }
        return false;
    }

    public static class Config extends TransformerConfig {

        private int maxAnalysisInstructions = 0;

        public Config() {
            super(InlineConstantValuesTransformer.class);
        }

        public int getMaxAnalysisInstructions() {
            return maxAnalysisInstructions;
        }

        public void setMaxAnalysisInstructions(int maxAnalysisInstructions) {
            this.maxAnalysisInstructions = maxAnalysisInstructions;
        }
    }
}
