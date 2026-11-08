package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleOpaquePredicates extends DryRunRule {
    @Override
    public String getDescription() {
        return "Conditions that always evaluate the same way are used as opaque predicates to confuse control flow";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return OpaquePredicateFoldingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found opaque predicates";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(OpaquePredicateFoldingTransformer.class, UniversalFlowTransformer.class);
    }
}
