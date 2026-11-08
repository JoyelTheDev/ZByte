package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleRedundantSwitches extends DryRunRule {
    @Override
    public String getDescription() {
        return "Switches on a constant key are used to hide the real control flow";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return CleanRedundantSwitchesTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found switches with a constant key";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(UniversalFlowTransformer.class, CleanRedundantSwitchesTransformer.class);
    }
}
