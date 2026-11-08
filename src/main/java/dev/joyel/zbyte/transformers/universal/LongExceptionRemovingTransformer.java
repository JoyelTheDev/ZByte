package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

@TransformerConfig.ConfigOptions(configClass = LongExceptionRemovingTransformer.Config.class)
public class LongExceptionRemovingTransformer extends Transformer<LongExceptionRemovingTransformer.Config> {

    @Override
    public boolean transform() throws Throwable {
        int max = getConfig().getMaxLength();
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode method : classNode.methods) {
                if (method.exceptions != null) {
                    changed |= method.exceptions.removeIf(name -> name.length() > max);
                }
            }
        }
        return changed;
    }

    public static class Config extends TransformerConfig {

        private int maxLength = 150;

        public Config() {
            super(LongExceptionRemovingTransformer.class);
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }
    }
}
