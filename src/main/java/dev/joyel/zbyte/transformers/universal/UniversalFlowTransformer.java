package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.general.peephole.DeadCodeRemover;

@TransformerConfig.ConfigOptions(configClass = UniversalFlowTransformer.Config.class)
public class UniversalFlowTransformer extends Transformer<UniversalFlowTransformer.Config> {

    @Override
    public boolean transform() throws Throwable {
        boolean changedOverall = false;
        boolean changed;
        int pass = 0;
        do {
            changed = false;
            CleanRedundantJumpsTransformer.Config jumps = new CleanRedundantJumpsTransformer.Config();
            jumps.setMaxAnalysisInstructions(getConfig().getMaxAnalysisInstructions());
            CleanRedundantSwitchesTransformer.Config switches = new CleanRedundantSwitchesTransformer.Config();
            switches.setMaxAnalysisInstructions(getConfig().getMaxAnalysisInstructions());
            UniversalNumberTransformer.Config numbers = new UniversalNumberTransformer.Config();
            numbers.setMaxAnalysisInstructions(getConfig().getMaxAnalysisInstructions());

            changed |= getDeobfuscator().runFromConfig(jumps);
            changed |= getDeobfuscator().runFromConfig(switches);
            changed |= getDeobfuscator().runFromConfig(numbers);
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(DeadCodeRemover.class));
            changedOverall |= changed;
            pass++;
        } while (changed && pass < getConfig().getMaxPasses());
        return changedOverall;
    }

    public static class Config extends TransformerConfig {

        private int maxPasses = 8;
        private int maxAnalysisInstructions = 0;

        public Config() {
            super(UniversalFlowTransformer.class);
        }

        public int getMaxPasses() {
            return maxPasses;
        }

        public void setMaxPasses(int maxPasses) {
            this.maxPasses = maxPasses;
        }

        public int getMaxAnalysisInstructions() {
            return maxAnalysisInstructions;
        }

        public void setMaxAnalysisInstructions(int maxAnalysisInstructions) {
            this.maxAnalysisInstructions = maxAnalysisInstructions;
        }
    }
}
