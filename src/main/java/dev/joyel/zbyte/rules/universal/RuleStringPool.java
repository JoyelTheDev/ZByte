package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleStringPool extends DryRunRule {
    @Override
    public String getDescription() {
        return "Strings may be moved into an array that is filled once and read everywhere to hide plain values";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return UniversalStringPoolTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found a string pool array";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(UniversalStringPoolTransformer.class);
    }
}
