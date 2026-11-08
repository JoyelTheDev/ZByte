package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleNumberPool extends DryRunRule {
    @Override
    public String getDescription() {
        return "Numbers may be moved into an array that is filled once and read everywhere to hide plain values";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return UniversalNumberPoolTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found a number pool array";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(UniversalNumberPoolTransformer.class);
    }
}
