package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public class AnnotationFilterTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            changed |= filter(classNode.invisibleAnnotations);
            changed |= filter(classNode.visibleAnnotations);
            for (MethodNode methodNode : classNode.methods) {
                changed |= filter(methodNode.invisibleAnnotations);
                changed |= filter(methodNode.visibleAnnotations);
                changed |= filterParameters(methodNode.invisibleParameterAnnotations);
                changed |= filterParameters(methodNode.visibleParameterAnnotations);
            }
            for (FieldNode fieldNode : classNode.fields) {
                changed |= filter(fieldNode.invisibleAnnotations);
                changed |= filter(fieldNode.visibleAnnotations);
            }
        }
        return changed;
    }

    private static boolean filter(List<AnnotationNode> annotations) {
        return annotations != null && annotations.removeIf(AnnotationFilterTransformer::isIllegal);
    }

    private static boolean filterParameters(List<AnnotationNode>[] parameterAnnotations) {
        boolean changed = false;
        if (parameterAnnotations != null) {
            for (List<AnnotationNode> annotations : parameterAnnotations) {
                changed |= filter(annotations);
            }
        }
        return changed;
    }

    private static boolean isIllegal(AnnotationNode annotation) {
        String desc = annotation.desc;
        return desc == null
                || desc.length() <= 3
                || !desc.startsWith("L")
                || !desc.endsWith(";")
                || desc.contains("\n")
                || desc.contains("\u0000")
                || desc.contains(" ");
    }
}
