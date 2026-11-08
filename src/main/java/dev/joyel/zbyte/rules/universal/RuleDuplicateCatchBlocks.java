package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleDuplicateCatchBlocks extends DryRunRule {
    @Override
    public String getDescription() {
        return "Identical catch handlers are copied many times by some obfuscators to bloat methods";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return DuplicateCatchMergingTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found duplicated catch handlers";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(DuplicateCatchMergingTransformer.class);
    }
}
