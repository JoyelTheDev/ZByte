package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;

@TransformerConfig.ConfigOptions(configClass = CleanRedundantSwitchesTransformer.Config.class)
public class CleanRedundantSwitchesTransformer extends Transformer<CleanRedundantSwitchesTransformer.Config> {

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
        if (!containsSwitch(methodNode)) {
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
            if (frame == null) {
                continue;
            }
            AbstractInsnNode insn = insns[i];
            if (insn.getOpcode() != TABLESWITCH && insn.getOpcode() != LOOKUPSWITCH) {
                continue;
            }
            Object key = frame.getStack(frame.getStackSize() - 1).getConstant();
            if (!(key instanceof Integer)) {
                continue;
            }
            int value = (Integer) key;
            LabelNode target;
            if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode table = (TableSwitchInsnNode) insn;
                target = value >= table.min && value <= table.max ? table.labels.get(value - table.min) : table.dflt;
            } else {
                LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) insn;
                int index = lookup.keys.indexOf(value);
                target = index >= 0 ? lookup.labels.get(index) : lookup.dflt;
            }
            methodNode.instructions.insertBefore(insn, new InsnNode(POP));
            methodNode.instructions.set(insn, new JumpInsnNode(GOTO, target));
            changed = true;
        }
        return changed;
    }

    private static boolean containsSwitch(MethodNode methodNode) {
        for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
            if (insn.getOpcode() == TABLESWITCH || insn.getOpcode() == LOOKUPSWITCH) {
                return true;
            }
        }
        return false;
    }

    public static class Config extends TransformerConfig {

        private int maxAnalysisInstructions = 0;

        public Config() {
            super(CleanRedundantSwitchesTransformer.class);
        }

        public int getMaxAnalysisInstructions() {
            return maxAnalysisInstructions;
        }

        public void setMaxAnalysisInstructions(int maxAnalysisInstructions) {
            this.maxAnalysisInstructions = maxAnalysisInstructions;
        }
    }
}
