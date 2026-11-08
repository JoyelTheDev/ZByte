package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleLongAnnotations extends DryRunRule {
    @Override
    public String getDescription() {
        return "Annotations with extremely long type names are used to crash decompilers and class parsers";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return LongAnnotationRemovingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found annotations with extremely long names";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(LongAnnotationRemovingTransformer.class);
    }
}
