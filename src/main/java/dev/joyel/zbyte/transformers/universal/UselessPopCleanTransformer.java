package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

public class UselessPopCleanTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                boolean progress;
                do {
                    progress = clean(methodNode);
                    changed |= progress;
                } while (progress);
            }
        }
        return changed;
    }

    private boolean clean(MethodNode methodNode) {
        Set<LabelNode> referenced = UniversalSupport.referencedLabels(methodNode);
        for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
            int opcode = insn.getOpcode();
            if (opcode != POP && opcode != POP2) {
                continue;
            }
            AbstractInsnNode producer = UniversalSupport.previous(insn, referenced);
            if (producer == null) {
                continue;
            }
            int size = producedSize(producer);
            if (opcode == POP && size == 1) {
                methodNode.instructions.remove(producer);
                methodNode.instructions.remove(insn);
                return true;
            }
            if (opcode == POP2 && size == 2) {
                methodNode.instructions.remove(producer);
                methodNode.instructions.remove(insn);
                return true;
            }
            if (opcode == POP2 && size == 1) {
                AbstractInsnNode second = UniversalSupport.previous(producer, referenced);
                if (second != null && producedSize(second) == 1) {
                    methodNode.instructions.remove(second);
                    methodNode.instructions.remove(producer);
                    methodNode.instructions.remove(insn);
                    return true;
                }
            }
        }
        return false;
    }

    private static int producedSize(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        switch (opcode) {
            case ACONST_NULL:
            case ICONST_M1:
            case ICONST_0:
            case ICONST_1:
            case ICONST_2:
            case ICONST_3:
            case ICONST_4:
            case ICONST_5:
            case FCONST_0:
            case FCONST_1:
            case FCONST_2:
            case BIPUSH:
            case SIPUSH:
            case ILOAD:
            case FLOAD:
            case ALOAD:
            case DUP:
                return 1;
            case LCONST_0:
            case LCONST_1:
            case DCONST_0:
            case DCONST_1:
            case LLOAD:
            case DLOAD:
            case DUP2:
                return 2;
            case LDC: {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Long || cst instanceof Double) {
                    return 2;
                }
                if (cst instanceof Integer || cst instanceof Float || cst instanceof String) {
                    return 1;
                }
                return 0;
            }
            default:
                return 0;
        }
    }
}
