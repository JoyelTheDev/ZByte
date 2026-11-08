package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.AsmFlowUtil;
import java.util.*;

import org.objectweb.asm.tree.*;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class DuplicateAnnotationRemovingTransformer extends Transformer<TransformerConfig> {
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

            if (dedup(classNode.visibleAnnotations)) markChange();
            if (dedup(classNode.invisibleAnnotations)) markChange();
            if (dedupType(classNode.visibleTypeAnnotations)) markChange();
            if (dedupType(classNode.invisibleTypeAnnotations)) markChange();

            for (FieldNode field : classNode.fields) {
                if (dedup(field.visibleAnnotations)) markChange();
                if (dedup(field.invisibleAnnotations)) markChange();
                if (dedupType(field.visibleTypeAnnotations)) markChange();
                if (dedupType(field.invisibleTypeAnnotations)) markChange();
            }

            for (MethodNode method : classNode.methods) {
                if (dedup(method.visibleAnnotations)) markChange();
                if (dedup(method.invisibleAnnotations)) markChange();
                if (dedupType(method.visibleTypeAnnotations)) markChange();
                if (dedupType(method.invisibleTypeAnnotations)) markChange();
                if (dedupType(method.visibleLocalVariableAnnotations)) markChange();
                if (dedupType(method.invisibleLocalVariableAnnotations)) markChange();
                if (method.visibleParameterAnnotations != null)
                    for (List<AnnotationNode> paramAnnos : method.visibleParameterAnnotations)
                        if (dedup(paramAnnos)) markChange();
                if (method.invisibleParameterAnnotations != null)
                    for (List<AnnotationNode> paramAnnos : method.invisibleParameterAnnotations)
                        if (dedup(paramAnnos)) markChange();
            }
        }
    }

    private static boolean dedup(List<AnnotationNode> annotations) {
        if (annotations == null || annotations.size() <= 1) return false;
        Set<String> seen = new HashSet<>();
        boolean changed = false;
        Iterator<AnnotationNode> iter = annotations.iterator();
        while (iter.hasNext()) {
            AnnotationNode anno = iter.next();
            if (!seen.add(anno.desc)) {
                iter.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static boolean dedupType(List<? extends AnnotationNode> annotations) {
        if (annotations == null || annotations.size() <= 1) return false;
        Set<String> seen = new HashSet<>();
        boolean changed = false;
        Iterator<? extends AnnotationNode> iter = annotations.iterator();
        while (iter.hasNext()) {
            AnnotationNode anno = iter.next();
            String key = anno.desc;
            if (anno instanceof TypeAnnotationNode ta)
                key = ta.typeRef + ":" + ta.typePath + ":" + anno.desc;
            if (!seen.add(key)) {
                iter.remove();
                changed = true;
            }
        }
        return changed;
    }
}
