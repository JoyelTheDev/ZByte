package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.universal.AntiTamperTransformer;
import java.util.Arrays;
import java.util.List;

public class RuleAntiTamper extends DryRunRule {

    @Override
    public String getDescription() {
        return "Debugger and integrity checks that terminate the program stop emulation and any modified jar from running";
    }

    @Override
    protected Class<? extends Transformer<?>> probe() {
        return AntiTamperTransformer.class;
    }

    @Override
    protected String describeFinding() {
        return "Found anti-debug or integrity checks that can exit the JVM";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Class<? extends Transformer<?>>> recommended() {
        return Arrays.<Class<? extends Transformer<?>>>asList(AntiTamperTransformer.class);
    }
}
