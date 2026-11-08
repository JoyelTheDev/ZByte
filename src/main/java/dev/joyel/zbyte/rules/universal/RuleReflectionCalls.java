package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleReflectionCalls extends DryRunRule {
    @Override
    public String getDescription() {
        return "Reflective lookups with constant targets hide direct calls and field accesses";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return ReflectionResolverTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found reflective calls that can be resolved";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(ReflectionResolverTransformer.class);
    }
}
