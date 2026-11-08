package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleBrokenTryCatch extends DryRunRule {
    @Override
    public String getDescription() {
        return "Some obfuscators emit try-catch blocks with missing labels or an empty or inverted range. This may crash decompilers";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return TryCatchRepairTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found invalid try-catch blocks";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(TryCatchRepairTransformer.class);
    }
}
