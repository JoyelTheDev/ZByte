package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleOpaqueConstants extends DryRunRule {
    @Override
    public String getDescription() {
        return "Constant values hidden behind stack manipulation and arithmetic chains are used to hide plain numbers and strings";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return OpaqueConstantFoldingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found constant expressions hidden behind stack manipulation";
    }

    @Override
    protected int minimumChanges() {
        return 3;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(OpaqueConstantFoldingTransformer.class, UniversalNumberTransformer.class);
    }
}
