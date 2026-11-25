package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.general.removers.IllegalClassAttributeRemover;
import java.util.Arrays;
import java.util.List;

public class RuleIllegalClassAttributes extends DryRunRule {

    @Override
    public String getDescription() {
        return "Attributes that point to missing classes or loop back on themselves are used to crash decompilers";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return IllegalClassAttributeRemover.class;
    }

    @Override
    protected String describeFinding() {
        return "Found class attributes that reference missing classes";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(IllegalClassAttributeRemover.class);
    }
}
