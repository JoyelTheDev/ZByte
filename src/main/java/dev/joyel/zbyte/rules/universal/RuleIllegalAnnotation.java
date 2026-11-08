package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleIllegalAnnotation extends DryRunRule {
    @Override
    public String getDescription() {
        return "Some obfuscators add annotations with malformed descriptors. This may crash decompilers and class parsers";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return AnnotationFilterTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found annotations with illegal descriptors";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(AnnotationFilterTransformer.class);
    }
}
