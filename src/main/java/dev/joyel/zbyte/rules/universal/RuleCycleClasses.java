package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleCycleClasses extends DryRunRule {
    @Override
    public String getDescription() {
        return "Classes that inherit from each other in a loop cannot be loaded and are used to crash analysis tools";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return CycleClassRemovingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found classes with a cyclic hierarchy";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(CycleClassRemovingTransformer.class);
    }
}
