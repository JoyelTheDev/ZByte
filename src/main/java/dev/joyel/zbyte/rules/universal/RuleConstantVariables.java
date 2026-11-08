package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleConstantVariables extends DryRunRule {
    @Override
    public String getDescription() {
        return "Local variables that only ever hold one constant value are used to hide plain values";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return VariableFoldingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found local variables that only hold constants";
    }

    @Override
    protected int minimumChanges() {
        return 10;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(VariableFoldingTransformer.class);
    }
}
