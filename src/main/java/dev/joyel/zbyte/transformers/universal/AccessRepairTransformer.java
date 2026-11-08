package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;

@TransformerConfig.ConfigOptions(configClass = AccessRepairTransformer.Config.class)
public class AccessRepairTransformer extends Transformer<AccessRepairTransformer.Config> {

    private static final int CLASS_MASK = ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_INTERFACE | ACC_ABSTRACT | ACC_SYNTHETIC | ACC_ANNOTATION | ACC_ENUM | ACC_MODULE;
    private static final int FIELD_MASK = ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED | ACC_STATIC | ACC_FINAL | ACC_VOLATILE | ACC_TRANSIENT | ACC_SYNTHETIC | ACC_ENUM;
    private static final int METHOD_MASK = ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED | ACC_STATIC | ACC_FINAL | ACC_SYNCHRONIZED | ACC_BRIDGE | ACC_VARARGS | ACC_NATIVE | ACC_ABSTRACT | ACC_STRICT | ACC_SYNTHETIC;
    private static final int PARAMETER_MASK = ACC_FINAL | ACC_SYNTHETIC | ACC_MANDATED;

    private int repaired;

    @Override
    public boolean transform() throws Throwable {
        repaired = 0;
        boolean strip = getConfig().isRemoveSynthetic();
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            int classAccess = repair(classNode.access, CLASS_MASK, strip ? ACC_SYNTHETIC : 0);
            if ((classAccess & ACC_INTERFACE) != 0) {
                classAccess |= ACC_ABSTRACT;
                classAccess &= ~ACC_FINAL;
            } else {
                classAccess &= ~ACC_ANNOTATION;
            }
            changed |= update(classNode.access, classAccess);
            classNode.access = classAccess;

            boolean isEnum = (classNode.access & ACC_ENUM) != 0;
            for (MethodNode methodNode : classNode.methods) {
                int access = repair(methodNode.access, METHOD_MASK, strip ? (ACC_SYNTHETIC | ACC_BRIDGE) : 0);
                if (methodNode.instructions.size() > 0) {
                    access &= ~(ACC_ABSTRACT | ACC_NATIVE);
                }
                changed |= update(methodNode.access, access);
                methodNode.access = access;
                if (methodNode.parameters != null) {
                    for (ParameterNode parameter : methodNode.parameters) {
                        int parameterAccess = repair(parameter.access, PARAMETER_MASK, strip ? ACC_SYNTHETIC : 0);
                        changed |= update(parameter.access, parameterAccess);
                        parameter.access = parameterAccess;
                    }
                }
            }
            for (FieldNode fieldNode : classNode.fields) {
                int access = repair(fieldNode.access, FIELD_MASK, strip ? ACC_SYNTHETIC : 0);
                if (!isEnum) {
                    access &= ~ACC_ENUM;
                }
                changed |= update(fieldNode.access, access);
                fieldNode.access = access;
            }
        }
        if (repaired > 0) {
            logger.info("Repaired {} access flags", repaired);
        }
        return changed;
    }

    private static int repair(int access, int mask, int extraRemoved) {
        return access & mask & ~extraRemoved;
    }

    private boolean update(int before, int after) {
        if (before == after) {
            return false;
        }
        repaired++;
        return true;
    }

    public static class Config extends TransformerConfig {

        private boolean removeSynthetic;

        public Config() {
            super(AccessRepairTransformer.class);
        }

        public boolean isRemoveSynthetic() {
            return removeSynthetic;
        }

        public void setRemoveSynthetic(boolean removeSynthetic) {
            this.removeSynthetic = removeSynthetic;
        }
    }
}
