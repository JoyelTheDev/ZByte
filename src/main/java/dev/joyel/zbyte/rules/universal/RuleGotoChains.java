package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleGotoChains extends DryRunRule {
    @Override
    public String getDescription() {
        return "Chains of goto instructions that each jump to a single block are used to break up the control flow";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return GotoInliningTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found goto instructions that can be inlined";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(GotoInliningTransformer.class, DeadCodeRemovingTransformer.class);
    }
}
