package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;

@TransformerConfig.ConfigOptions(configClass = UniversalNumberTransformer.Config.class)
public class UniversalNumberTransformer extends Transformer<UniversalNumberTransformer.Config> {

    @Override
    @SuppressWarnings("unchecked")
    public boolean transform() throws Throwable {
        boolean changedOverall = false;
        boolean changed;
        int pass = 0;
        do {
            changed = false;
            changed |= getDeobfuscator().runFromConfig(inlineConstants());
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(EmptyArrayLengthTransformer.class));
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(UselessPopCleanTransformer.class));
            changedOverall |= changed;
            pass++;
        } while (changed && pass < getConfig().getMaxPasses());
        return changedOverall;
    }

    private TransformerConfig inlineConstants() {
        InlineConstantValuesTransformer.Config config = new InlineConstantValuesTransformer.Config();
        config.setMaxAnalysisInstructions(getConfig().getMaxAnalysisInstructions());
        return config;
    }

    public static class Config extends TransformerConfig {

        private int maxPasses = 8;
        private int maxAnalysisInstructions = 0;

        public Config() {
            super(UniversalNumberTransformer.class);
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
