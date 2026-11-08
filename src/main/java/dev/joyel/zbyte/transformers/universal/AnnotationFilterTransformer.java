package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public class AnnotationFilterTransformer extends Transformer<TransformerConfig> {

    private int removed;

    @Override
    public boolean transform() throws Throwable {
        removed = 0;
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
        if (removed > 0) {
            logger.info("Removed {} illegal annotations", removed);
        }
        return changed;
    }

    private boolean filter(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        int before = annotations.size();
        annotations.removeIf(AnnotationFilterTransformer::isIllegal);
        int diff = before - annotations.size();
        removed += diff;
        return diff > 0;
    }

    private boolean filterParameters(List<AnnotationNode>[] parameterAnnotations) {
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
