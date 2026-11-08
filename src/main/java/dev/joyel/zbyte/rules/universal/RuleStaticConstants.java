package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleStaticConstants extends DryRunRule {
    @Override
    public String getDescription() {
        return "Static fields that are assigned a constant once are used to hide plain values behind field reads";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return StaticValueInliningTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found static fields with a known constant value";
    }

    @Override
    protected int minimumChanges() {
        return 10;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(StaticValueInliningTransformer.class);
    }
}
