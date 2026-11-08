package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleLongExceptions extends DryRunRule {
    @Override
    public String getDescription() {
        return "Declared exceptions with extremely long names are used to crash decompilers and class parsers";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return LongExceptionRemovingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found declared exceptions with extremely long names";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(LongExceptionRemovingTransformer.class);
    }
}
