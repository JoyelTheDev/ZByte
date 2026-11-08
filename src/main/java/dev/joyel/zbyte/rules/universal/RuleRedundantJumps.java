package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleRedundantJumps extends DryRunRule {
    @Override
    public String getDescription() {
        return "Conditional jumps with a constant condition are used as opaque predicates to confuse control flow";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return CleanRedundantJumpsTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found conditional jumps with a constant condition";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(UniversalFlowTransformer.class, CleanRedundantJumpsTransformer.class);
    }
}
