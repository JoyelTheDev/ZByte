package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;

@TransformerConfig.ConfigOptions(configClass = CleanRedundantJumpsTransformer.Config.class)
public class CleanRedundantJumpsTransformer extends Transformer<CleanRedundantJumpsTransformer.Config> {

    @Override
    public boolean transform() throws Throwable {
        int limit = getConfig().getMaxAnalysisInstructions();
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : new ArrayList<>(classNode.methods)) {
                if (limit > 0 && methodNode.instructions.size() > limit) {
                    continue;
                }
                changed |= process(classNode, methodNode);
            }
        }
        return changed;
    }

    private boolean process(ClassNode classNode, MethodNode methodNode) {
        if (!containsConditionalJump(methodNode)) {
            return false;
        }
        Frame<ConstantAnalysis.Value>[] frames = ConstantAnalysis.analyze(classNode, methodNode);
        if (frames == null) {
            return false;
        }
        AbstractInsnNode[] insns = methodNode.instructions.toArray();
        boolean changed = false;
        for (int i = 0; i < insns.length; i++) {
            Frame<ConstantAnalysis.Value> frame = frames[i];
            if (frame == null || !(insns[i] instanceof JumpInsnNode)) {
                continue;
            }
            JumpInsnNode jump = (JumpInsnNode) insns[i];
            int opcode = jump.getOpcode();
            boolean one = opcode >= IFEQ && opcode <= IFLE;
            boolean two = opcode >= IF_ICMPEQ && opcode <= IF_ICMPLE;
            if (!one && !two) {
                continue;
            }
            int top = frame.getStackSize() - 1;
            Object right = frame.getStack(top).getConstant();
            Object left = two ? frame.getStack(top - 1).getConstant() : null;
            if (!(right instanceof Integer) || (two && !(left instanceof Integer))) {
                continue;
            }
            boolean taken = evaluate(opcode, two ? (Integer) left : 0, (Integer) right);
            methodNode.instructions.insertBefore(jump, new InsnNode(POP));
            if (two) {
                methodNode.instructions.insertBefore(jump, new InsnNode(POP));
            }
            if (taken) {
                jump.setOpcode(GOTO);
            } else {
                methodNode.instructions.remove(jump);
            }
            changed = true;
        }
        return changed;
    }

    private static boolean evaluate(int opcode, int left, int right) {
        switch (opcode) {
            case IFEQ:
                return right == 0;
            case IFNE:
                return right != 0;
            case IFLT:
                return right < 0;
            case IFGE:
                return right >= 0;
            case IFGT:
                return right > 0;
            case IFLE:
                return right <= 0;
            case IF_ICMPEQ:
                return left == right;
            case IF_ICMPNE:
                return left != right;
            case IF_ICMPLT:
                return left < right;
            case IF_ICMPGE:
                return left >= right;
            case IF_ICMPGT:
                return left > right;
            default:
                return left <= right;
        }
    }

    private static boolean containsConditionalJump(MethodNode methodNode) {
        for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
            int opcode = insn.getOpcode();
            if (opcode >= IFEQ && opcode <= IF_ICMPLE) {
                return true;
            }
        }
        return false;
    }

    public static class Config extends TransformerConfig {

        private int maxAnalysisInstructions = 0;

        public Config() {
            super(CleanRedundantJumpsTransformer.class);
        }

        public int getMaxAnalysisInstructions() {
            return maxAnalysisInstructions;
        }

        public void setMaxAnalysisInstructions(int maxAnalysisInstructions) {
            this.maxAnalysisInstructions = maxAnalysisInstructions;
        }
    }
}
