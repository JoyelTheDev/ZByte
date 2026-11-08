package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleConstantArithmetic extends DryRunRule {
    @Override
    public String getDescription() {
        return "Arithmetic on constant values is a common way to hide plain numbers";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return InlineConstantValuesTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found arithmetic on constant values";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(UniversalNumberTransformer.class, InlineConstantValuesTransformer.class);
    }
}
