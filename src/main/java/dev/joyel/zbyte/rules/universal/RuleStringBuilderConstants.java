package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleStringBuilderConstants extends DryRunRule {
    @Override
    public String getDescription() {
        return "String concatenation made only of constants was compiled to a StringBuilder chain, which is a common way to hide strings";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return StringBuilderTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found StringBuilder chains made only of constants";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(StringBuilderTransformer.class);
    }
}
