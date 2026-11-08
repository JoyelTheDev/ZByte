package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.*;

import java.util.Arrays;
import java.util.List;

public class RuleClassForNameJunk extends DryRunRule {
    @Override
    public String getDescription() {
        return "Some obfuscators insert Class.forName calls whose result is discarded. They only add noise. Set classNames to * to remove all of them";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return ClassForNameTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found Class.forName calls whose result is discarded";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(ClassForNameTransformer.class);
    }
}
