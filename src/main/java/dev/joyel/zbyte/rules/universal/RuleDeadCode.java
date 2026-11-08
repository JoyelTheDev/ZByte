package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleDeadCode extends DryRunRule {
    @Override
    public String getDescription() {
        return "Unreachable instructions are left behind by flow obfuscation and make methods harder to read";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return DeadCodeRemovingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found unreachable code";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(DeadCodeRemovingTransformer.class);
    }
}
