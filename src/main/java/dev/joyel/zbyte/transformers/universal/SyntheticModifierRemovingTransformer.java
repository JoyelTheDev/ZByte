package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.*;

import org.objectweb.asm.tree.*;

public class SyntheticModifierRemovingTransformer extends Transformer<TransformerConfig> {
    private boolean changed;

    private void markChange() {
        changed = true;
    }

    @Override
    public boolean transform() throws Throwable {
        changed = false;
        run();
        return changed;
    }

    private void run() throws Exception {
        for (ClassNode classNode : new ArrayList<>(classNodes())) {

            int prevAccess = classNode.access;
            classNode.access &= ~ACC_SYNTHETIC;
            if (prevAccess != classNode.access) markChange();

            for (FieldNode field : classNode.fields) {
                int prev = field.access;
                field.access &= ~ACC_SYNTHETIC;
                if (prev != field.access) markChange();
            }

            for (MethodNode method : classNode.methods) {
                int prev = method.access;
                method.access &= ~(ACC_SYNTHETIC | ACC_BRIDGE);
                if (prev != method.access) markChange();
            }
        }
    }
}
