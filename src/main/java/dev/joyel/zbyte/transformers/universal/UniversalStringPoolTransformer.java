package dev.joyel.zbyte.transformers.universal;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Collections;
import java.util.Set;

public class UniversalStringPoolTransformer extends AbstractPoolTransformer {

    private static final String POOL_DESC = "[Ljava/lang/String;";

    @Override
    protected boolean creationMatches(AbstractInsnNode creation, String fieldDesc) {
        return fieldDesc.equals(POOL_DESC)
                && creation.getOpcode() == ANEWARRAY
                && ((TypeInsnNode) creation).desc.equals("java/lang/String");
    }

    @Override
    protected Parsed readStoredValue(AbstractInsnNode start, String fieldDesc, Set<LabelNode> referenced) {
        Object value = UniversalSupport.constantValue(start);
        return value instanceof String ? new Parsed(value, start) : null;
    }

    @Override
    protected boolean isStore(AbstractInsnNode insn, String fieldDesc) {
        return insn.getOpcode() == AASTORE;
    }

    @Override
    protected Load readLoad(AbstractInsnNode start, String fieldDesc, Set<LabelNode> referenced) {
        if (!fieldDesc.equals(POOL_DESC) || start == null || start.getOpcode() != AALOAD) {
            return null;
        }
        return new Load(Collections.singletonList(start), 'A');
    }

    @Override
    protected Object defaultValue(String fieldDesc) {
        return null;
    }

    @Override
    protected Object normalize(Object value, String fieldDesc) {
        return value;
    }
}
