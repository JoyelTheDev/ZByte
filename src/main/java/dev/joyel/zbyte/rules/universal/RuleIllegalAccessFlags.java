package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleIllegalAccessFlags extends DryRunRule {
    @Override
    public String getDescription() {
        return "Some obfuscators set access flags that are illegal for a class, field, method or parameter. Strict tools may refuse to load such classes";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return AccessRepairTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found illegal access flags";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(AccessRepairTransformer.class);
    }
}
