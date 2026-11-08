package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;

@TransformerConfig.ConfigOptions(configClass = ReflectionResolverWidenTransformer.Config.class)
public class ReflectionResolverWidenTransformer extends ReflectionResolverTransformer {

    @Override
    protected boolean widenAccess() {
        return true;
    }

    public static class Config extends ReflectionResolverTransformer.Config {

        public Config() {
            super(ReflectionResolverWidenTransformer.class);
        }
    }
}
