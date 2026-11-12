package dev.joyel.zbyte.transformers.zkm;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.CallResultInliningTransformer;
import dev.joyel.zbyte.transformers.universal.InvokeDynamicInliningTransformer;
import dev.joyel.zbyte.transformers.universal.OpaqueConstantFoldingTransformer;
import dev.joyel.zbyte.transformers.universal.VariableFoldingTransformer;
import dev.joyel.zbyte.transformers.universal.UniversalFlowTransformer;

@TransformerConfig.ConfigOptions(configClass = ZkmTransformer.Config.class)
public class ZkmTransformer extends Transformer<ZkmTransformer.Config> {

    @Override
    public boolean transform() throws Throwable {
        boolean changedOverall = false;
        for (int pass = 0; pass < getConfig().getMaxPasses(); pass++) {
            boolean changed = false;
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(ZkmParameterUnpackingTransformer.class));
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(OpaqueConstantFoldingTransformer.class));
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(VariableFoldingTransformer.class));
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(InvokeDynamicInliningTransformer.class));
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(CallResultInliningTransformer.class));
            changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(ZkmDecryptionCleanupTransformer.class));
            if (getConfig().isCleanFlow()) {
                changed |= getDeobfuscator().runFromConfig(TransformerConfig.configFor(UniversalFlowTransformer.class));
            }
            changedOverall |= changed;
            if (!changed) {
                break;
            }
        }
        return changedOverall;
    }

    public static class Config extends TransformerConfig {

        private boolean cleanFlow;
        private int maxPasses = 8;

        public Config() {
            super(ZkmTransformer.class);
        }

        public int getMaxPasses() {
            return maxPasses;
        }

        public void setMaxPasses(int maxPasses) {
            this.maxPasses = maxPasses;
        }

        public boolean isCleanFlow() {
            return cleanFlow;
        }

        public void setCleanFlow(boolean cleanFlow) {
            this.cleanFlow = cleanFlow;
        }
    }
}
