package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleRedundantTryCatch extends DryRunRule {
    @Override
    public String getDescription() {
        return "Try-catch blocks around code that cannot throw the caught exception are used to confuse control flow";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return RedundantTryCatchRemovingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found try-catch blocks that can never catch anything";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(RedundantTryCatchRemovingTransformer.class);
    }
}
