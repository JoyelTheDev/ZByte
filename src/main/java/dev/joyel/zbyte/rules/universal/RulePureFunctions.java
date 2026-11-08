package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RulePureFunctions extends DryRunRule {
    @Override
    public String getDescription() {
        return "Many tiny static helper methods without side effects are often used to hide simple expressions behind calls";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return InlinePureFunctionsTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found many inlinable pure helper methods";
    }

    @Override
    protected int minimumChanges() {
        return 10;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(InlinePureFunctionsTransformer.class);
    }
}
