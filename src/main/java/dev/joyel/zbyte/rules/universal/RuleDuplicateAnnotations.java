package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleDuplicateAnnotations extends DryRunRule {
    @Override
    public String getDescription() {
        return "The same annotation is repeated on one element. Some obfuscators do this to crash decompilers";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return DuplicateAnnotationRemovingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found duplicated annotations";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(DuplicateAnnotationRemovingTransformer.class);
    }
}
