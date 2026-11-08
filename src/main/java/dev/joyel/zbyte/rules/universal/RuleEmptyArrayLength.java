package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleEmptyArrayLength extends DryRunRule {
    @Override
    public String getDescription() {
        return "Arrays created only to read their length are used as junk code to hide plain numbers";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return EmptyArrayLengthTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found array creations used only for their length";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(EmptyArrayLengthTransformer.class, UniversalNumberTransformer.class);
    }
}
