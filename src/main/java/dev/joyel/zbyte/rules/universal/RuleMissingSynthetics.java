package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleMissingSynthetics extends DryRunRule {
    @Override
    public String getDescription() {
        return "Lambda bodies and accessor methods may have lost their synthetic flag, which makes decompiled output harder to read";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return RecoverSyntheticsTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found lambda or accessor methods without the synthetic flag";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(RecoverSyntheticsTransformer.class);
    }
}
