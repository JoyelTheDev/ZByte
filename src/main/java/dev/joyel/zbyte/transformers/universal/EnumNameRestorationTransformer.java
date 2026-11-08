package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.normalizer.AbstractNormalizer;
import dev.joyel.zbyte.transformers.normalizer.CustomRemapper;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

@TransformerConfig.ConfigOptions(configClass = EnumNameRestorationTransformer.Config.class)
public class EnumNameRestorationTransformer extends AbstractNormalizer<EnumNameRestorationTransformer.Config> {

    private static final String VALUES_ARRAY_NAME = "$values";

    @Override
    public void remap(CustomRemapper remapper) {
        for (ClassNode classNode : classNodes()) {
            if ((classNode.access & ACC_ENUM) == 0) {
                continue;
            }
            String constDesc = "L" + classNode.name + ";";
            String valuesDesc = "[" + constDesc;
            for (MethodNode method : classNode.methods) {
                if (!method.name.equals("<clinit>") || !method.desc.equals("()V")) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() == LDC) {
                        handleConstant(remapper, classNode, (LdcInsnNode) insn, constDesc);
                    } else if (insn.getOpcode() == INVOKESTATIC) {
                        handleValues(remapper, classNode, (MethodInsnNode) insn, valuesDesc);
                    }
                }
            }
        }
    }

    private static void handleConstant(CustomRemapper remapper, ClassNode classNode, LdcInsnNode nameInsn, String constDesc) {
        if (!(nameInsn.cst instanceof String) || !((String) nameInsn.cst).matches("\\w+")) {
            return;
        }
        String enumName = (String) nameInsn.cst;
        AbstractInsnNode indexInsn = AsmFlowUtil.getNextFollowGoto(nameInsn);
        if (indexInsn == null || !AsmFlowUtil.isConstIntValue(indexInsn)) {
            return;
        }
        AbstractInsnNode constructor = AsmFlowUtil.getNextFollowGoto(indexInsn);
        if (!(constructor instanceof MethodInsnNode) || constructor.getOpcode() != INVOKESPECIAL) {
            return;
        }
        MethodInsnNode call = (MethodInsnNode) constructor;
        if (!call.owner.equals(classNode.name) || !call.name.equals("<init>") || !call.desc.startsWith("(Ljava/lang/String;I")) {
            return;
        }
        FieldInsnNode assignment = findPutStatic(constructor);
        if (assignment == null || !assignment.desc.equals(constDesc) || assignment.name.equals(enumName)) {
            return;
        }
        boolean enumField = classNode.fields.stream().anyMatch(f -> f.name.equals(assignment.name)
                && f.desc.equals(assignment.desc) && (f.access & ACC_ENUM) != 0);
        if (enumField) {
            remapper.mapFieldName(classNode.name, assignment.name, assignment.desc, enumName, false);
        }
    }

    private static void handleValues(CustomRemapper remapper, ClassNode classNode, MethodInsnNode invoke, String valuesDesc) {
        if (!invoke.owner.equals(classNode.name) || !invoke.desc.equals("()" + valuesDesc)) {
            return;
        }
        FieldInsnNode assignment = findPutStatic(invoke);
        if (assignment != null && assignment.owner.equals(classNode.name)
                && assignment.desc.equals(valuesDesc) && !assignment.name.equals(VALUES_ARRAY_NAME)) {
            remapper.mapFieldName(classNode.name, assignment.name, valuesDesc, VALUES_ARRAY_NAME, false);
        }
    }

    private static FieldInsnNode findPutStatic(AbstractInsnNode from) {
        AbstractInsnNode next = AsmFlowUtil.getNextFollowGoto(from);
        int steps = 0;
        while (next != null && next.getOpcode() != PUTSTATIC && steps++ < 20) {
            next = AsmFlowUtil.getNextFollowGoto(next);
        }
        return next != null && next.getOpcode() == PUTSTATIC ? (FieldInsnNode) next : null;
    }

    public static class Config extends AbstractNormalizer.Config {

        public Config() {
            super(EnumNameRestorationTransformer.class);
        }
    }
}
