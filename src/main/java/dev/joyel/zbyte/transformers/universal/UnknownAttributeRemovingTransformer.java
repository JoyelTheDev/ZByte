package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.*;

import org.objectweb.asm.tree.*;

public class UnknownAttributeRemovingTransformer extends Transformer<TransformerConfig> {
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

            if (classNode.attrs != null && !classNode.attrs.isEmpty()) {
                classNode.attrs.clear();
                markChange();
            }
            for (FieldNode field : classNode.fields) {
                if (field.attrs != null && !field.attrs.isEmpty()) {
                    field.attrs.clear();
                    markChange();
                }
            }
            for (MethodNode method : classNode.methods) {
                if (method.attrs != null && !method.attrs.isEmpty()) {
                    method.attrs.clear();
                    markChange();
                }
            }
            if (classNode.recordComponents != null) {
                for (RecordComponentNode rc : classNode.recordComponents) {
                    if (rc.attrs != null && !rc.attrs.isEmpty()) {
                        rc.attrs.clear();
                        markChange();
                    }
                }
            }
        }
    }
}
