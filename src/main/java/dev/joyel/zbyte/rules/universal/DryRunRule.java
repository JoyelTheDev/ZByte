package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.ZByte;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.rules.Rule;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class DryRunRule implements Rule {

    protected abstract Class<? extends Transformer<?>> probe();
    protected abstract String describeFinding();

    protected int minimumChanges() {
        return 1;
    }

    protected List<Class<? extends Transformer<?>>> recommended() {
        return Collections.singletonList(probe());
    }

    @Override
    public Collection<Class<? extends Transformer<?>>> getRecommendTransformers() {
        return recommended();
    }

    @Override
    public String test(ZByte deobfuscator) {
        Map<String, ClassNode> originals = deobfuscator.getClasses();
        Map<String, ClassNode> copies = new LinkedHashMap<>();
        for (Map.Entry<String, ClassNode> entry : originals.entrySet()) {
            ClassNode copy = new ClassNode();
            entry.getValue().accept(copy);
            copies.put(entry.getKey(), copy);
        }
        try {
            Class<? extends Transformer<?>> type = probe();
            Transformer<?> transformer = type.getDeclaredConstructor().newInstance();
            transformer.init(deobfuscator, TransformerConfig.configFor(type), copies, copies, new HashMap<ClassNode, ClassReader>());
            if (!transformer.transform()) {
                return null;
            }
        } catch (Throwable e) {
            return null;
        }
        int changes = 0;
        String first = null;
        for (Map.Entry<String, ClassNode> entry : originals.entrySet()) {
            ClassNode copy = copies.get(entry.getKey());
            String location = copy == null ? entry.getKey() : firstDifference(entry.getValue(), copy);
            if (location != null) {
                changes++;
                if (first == null) {
                    first = location;
                }
            }
        }
        if (changes < minimumChanges()) {
            return null;
        }
        return describeFinding() + " in " + changes + " classes, first in " + first;
    }

    private static String firstDifference(ClassNode before, ClassNode after) {
        if (before.access != after.access || before.methods.size() != after.methods.size() || before.fields.size() != after.fields.size()) {
            return before.name;
        }
        if (annotations(before.visibleAnnotations) != annotations(after.visibleAnnotations)
                || annotations(before.invisibleAnnotations) != annotations(after.invisibleAnnotations)) {
            return before.name;
        }
        Map<String, FieldNode> fields = new HashMap<>();
        for (FieldNode field : after.fields) {
            fields.put(field.name + field.desc, field);
        }
        for (FieldNode field : before.fields) {
            FieldNode other = fields.get(field.name + field.desc);
            if (other == null || other.access != field.access
                    || annotations(field.visibleAnnotations) != annotations(other.visibleAnnotations)
                    || annotations(field.invisibleAnnotations) != annotations(other.invisibleAnnotations)) {
                return before.name + "." + field.name;
            }
        }
        Map<String, MethodNode> methods = new HashMap<>();
        for (MethodNode method : after.methods) {
            methods.put(method.name + method.desc, method);
        }
        for (MethodNode method : before.methods) {
            MethodNode other = methods.get(method.name + method.desc);
            if (other == null || other.access != method.access
                    || other.instructions.size() != method.instructions.size()
                    || other.tryCatchBlocks.size() != method.tryCatchBlocks.size()
                    || annotations(method.visibleAnnotations) != annotations(other.visibleAnnotations)
                    || annotations(method.invisibleAnnotations) != annotations(other.invisibleAnnotations)
                    || parameterAnnotations(method) != parameterAnnotations(other)) {
                return before.name + "." + method.name + method.desc;
            }
        }
        return null;
    }

    private static int annotations(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static int parameterAnnotations(MethodNode method) {
        int total = 0;
        if (method.visibleParameterAnnotations != null) {
            for (List<?> list : method.visibleParameterAnnotations) {
                total += annotations(list);
            }
        }
        if (method.invisibleParameterAnnotations != null) {
            for (List<?> list : method.invisibleParameterAnnotations) {
                total += annotations(list);
            }
        }
        return total;
    }
}
