package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.TransformerHelper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@TransformerConfig.ConfigOptions(configClass = ClassForNameTransformer.Config.class)
public class ClassForNameTransformer extends Transformer<ClassForNameTransformer.Config> {

    @Override
    public boolean transform() throws Throwable {
        Set<String> names = new HashSet<>(getConfig().getClassNames());
        boolean all = names.contains("*");
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                Set<LabelNode> referenced = UniversalSupport.referencedLabels(methodNode);
                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    if (!TransformerHelper.isConstantString(insn)) {
                        continue;
                    }
                    String name = ((LdcInsnNode) insn).cst.toString();
                    if (!all && !names.contains(name)) {
                        continue;
                    }
                    AbstractInsnNode call = UniversalSupport.next(insn, referenced);
                    if (!TransformerHelper.isInvokeStatic(call, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;")) {
                        continue;
                    }
                    AbstractInsnNode pop = UniversalSupport.next(call, referenced);
                    if (pop == null || pop.getOpcode() != POP) {
                        continue;
                    }
                    methodNode.instructions.remove(insn);
                    methodNode.instructions.remove(call);
                    methodNode.instructions.remove(pop);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public static class Config extends TransformerConfig {

        private List<String> classNames = new ArrayList<>(Arrays.asList("java.lang.Integer"));

        public Config() {
            super(ClassForNameTransformer.class);
        }

        public List<String> getClassNames() {
            return classNames;
        }

        public void setClassNames(List<String> classNames) {
            this.classNames = classNames;
        }
    }
}
