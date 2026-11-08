package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

@TransformerConfig.ConfigOptions(configClass = LongAnnotationRemovingTransformer.Config.class)
public class LongAnnotationRemovingTransformer extends Transformer<LongAnnotationRemovingTransformer.Config> {

    @Override
    public boolean transform() throws Throwable {
        int max = getConfig().getMaxLength();
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            changed |= filter(classNode.visibleAnnotations, max);
            changed |= filter(classNode.invisibleAnnotations, max);
            changed |= filter(classNode.visibleTypeAnnotations, max);
            changed |= filter(classNode.invisibleTypeAnnotations, max);
            for (FieldNode field : classNode.fields) {
                changed |= filter(field.visibleAnnotations, max);
                changed |= filter(field.invisibleAnnotations, max);
                changed |= filter(field.visibleTypeAnnotations, max);
                changed |= filter(field.invisibleTypeAnnotations, max);
            }
            for (MethodNode method : classNode.methods) {
                changed |= filter(method.visibleAnnotations, max);
                changed |= filter(method.invisibleAnnotations, max);
                changed |= filter(method.visibleTypeAnnotations, max);
                changed |= filter(method.invisibleTypeAnnotations, max);
                changed |= filter(method.visibleLocalVariableAnnotations, max);
                changed |= filter(method.invisibleLocalVariableAnnotations, max);
                changed |= filterParameters(method.visibleParameterAnnotations, max);
                changed |= filterParameters(method.invisibleParameterAnnotations, max);
            }
        }
        return changed;
    }

    private static boolean filterParameters(List<AnnotationNode>[] parameters, int max) {
        boolean changed = false;
        if (parameters != null) {
            for (List<AnnotationNode> annotations : parameters) {
                changed |= filter(annotations, max);
            }
        }
        return changed;
    }

    private static boolean filter(List<? extends AnnotationNode> annotations, int max) {
        return annotations != null && annotations.removeIf(a -> a.desc != null && a.desc.length() >= max);
    }

    public static class Config extends TransformerConfig {

        private int maxLength = 150;

        public Config() {
            super(LongAnnotationRemovingTransformer.class);
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }
    }
}
