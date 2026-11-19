package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@TransformerConfig.ConfigOptions(configClass = UnusedClassRemoverTransformer.Config.class)
public class UnusedClassRemoverTransformer extends Transformer<UnusedClassRemoverTransformer.Config> {
    private static final Set<String> PLAIN_SUPERTYPES = new HashSet<>(Arrays.asList(
            "java/lang/Object", "java/lang/Enum", 
            "java/lang/Record", "java/io/Serializable", 
            "java/lang/Cloneable"));

    private Config settings() {
        Config config = getConfig();
        return config == null ? new Config() : config;
    }

    @Override
    public boolean transform() throws Throwable {
        Config config = settings();
        Map<String, ClassNode> inputs = new LinkedHashMap<>();
        for (ClassNode node : classNodes()) {
            inputs.put(node.name, node);
        }
        Set<String> reachable = new HashSet<>();
        Deque<String> work = new ArrayDeque<>();
        for (ClassNode node : inputs.values()) {
            if (isRoot(node, inputs, config) && reachable.add(node.name)) {
                work.add(node.name);
            }
        }
        if (work.isEmpty()) {
            logger.warn("No entry points found (main method, framework classes or configured roots); keeping every class");
            return false;
        }
        while (!work.isEmpty()) {
            ClassNode node = inputs.get(work.poll());
            collectReferences(node, inputs, name -> {
                if (inputs.containsKey(name) && reachable.add(name)) {
                    work.add(name);
                }
            });
        }
        Set<String> removed = new HashSet<>();
        for (String name : inputs.keySet()) {
            if (!reachable.contains(name)) {
                removed.add(name);
            }
        }
        if (removed.isEmpty()) {
            return false;
        }
        for (String name : removed) {
            logger.debug("Removing unused class {}", name);
            classes.remove(name);
            classpath.remove(name);
        }
        for (String name : reachable) {
            prune(inputs.get(name), removed);
        }
        return true;
    }

    private void prune(ClassNode node, Set<String> removed) {
        if (node.nestMembers != null) {
            node.nestMembers.removeIf(removed::contains);
        }
        if (node.permittedSubclasses != null) {
            node.permittedSubclasses.removeIf(removed::contains);
        }
        if (node.innerClasses != null) {
            node.innerClasses.removeIf(inner -> removed.contains(inner.name));
        }
    }

    private boolean isRoot(ClassNode node, Map<String, ClassNode> inputs, Config config) {
        for (String root : config.getRoots()) {
            if (root.endsWith("/") ? node.name.startsWith(root) : node.name.equals(root)) {
                return true;
            }
        }
        if (config.isKeepPublic() && (node.access & ACC_PUBLIC) != 0) {
            return true;
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V")
                    && (method.access & ACC_PUBLIC) != 0 && (method.access & ACC_STATIC) != 0) {
                return true;
            }
        }
        if (!config.isKeepFrameworkClasses()) {
            return false;
        }
        if (node.superName != null && !inputs.containsKey(node.superName) && !PLAIN_SUPERTYPES.contains(node.superName)) {
            return true;
        }
        if (node.interfaces != null) {
            for (String itf : node.interfaces) {
                if (!inputs.containsKey(itf) && !PLAIN_SUPERTYPES.contains(itf)) {
                    return true;
                }
            }
        }
        return hasLibraryAnnotation(node.visibleAnnotations, inputs) || hasLibraryAnnotation(node.invisibleAnnotations, inputs);
    }

    private boolean hasLibraryAnnotation(List<AnnotationNode> annotations, Map<String, ClassNode> inputs) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotation : annotations) {
            Type type = Type.getType(annotation.desc);
            if (!inputs.containsKey(type.getInternalName()) && !type.getInternalName().startsWith("java/lang/")) {
                return true;
            }
        }
        return false;
    }

    private void collectReferences(ClassNode node, Map<String, ClassNode> inputs, Consumer<String> sink) {
        internal(node.superName, sink);
        internal(node.outerClass, sink);
        internal(node.nestHostClass, sink);
        if (node.interfaces != null) {
            node.interfaces.forEach(name -> internal(name, sink));
        }
        annotations(node.visibleAnnotations, sink);
        annotations(node.invisibleAnnotations, sink);
        for (FieldNode field : node.fields) {
            descriptor(field.desc, sink);
            annotations(field.visibleAnnotations, sink);
            annotations(field.invisibleAnnotations, sink);
            if (field.value instanceof String) {
                string((String) field.value, inputs, sink);
            }
        }
        for (MethodNode method : node.methods) {
            descriptor(method.desc, sink);
            if (method.exceptions != null) {
                method.exceptions.forEach(name -> internal(name, sink));
            }
            annotations(method.visibleAnnotations, sink);
            annotations(method.invisibleAnnotations, sink);
            parameterAnnotations(method.visibleParameterAnnotations, sink);
            parameterAnnotations(method.invisibleParameterAnnotations, sink);
            if (method.annotationDefault != null) {
                annotationValue(method.annotationDefault, sink);
            }
            if (method.localVariables != null) {
                for (LocalVariableNode local : method.localVariables) {
                    descriptor(local.desc, sink);
                }
            }
            if (method.tryCatchBlocks != null) {
                for (TryCatchBlockNode block : method.tryCatchBlocks) {
                    internal(block.type, sink);
                }
            }
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                instruction(insn, inputs, sink);
            }
        }
    }

    private void instruction(AbstractInsnNode insn, Map<String, ClassNode> inputs, Consumer<String> sink) {
        if (insn instanceof TypeInsnNode) {
            internal(((TypeInsnNode) insn).desc, sink);
        } else if (insn instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) insn;
            internal(field.owner, sink);
            descriptor(field.desc, sink);
        } else if (insn instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) insn;
            internal(call.owner, sink);
            descriptor(call.desc, sink);
        } else if (insn instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
            descriptor(indy.desc, sink);
            constant(indy.bsm, inputs, sink);
            if (indy.bsmArgs != null) {
                for (Object arg : indy.bsmArgs) {
                    constant(arg, inputs, sink);
                }
            }
        } else if (insn instanceof LdcInsnNode) {
            constant(((LdcInsnNode) insn).cst, inputs, sink);
        } else if (insn instanceof MultiANewArrayInsnNode) {
            descriptor(((MultiANewArrayInsnNode) insn).desc, sink);
        }
    }

    private void constant(Object value, Map<String, ClassNode> inputs, Consumer<String> sink) {
        if (value instanceof String) {
            string((String) value, inputs, sink);
        } else if (value instanceof Type) {
            Type type = (Type) value;
            if (type.getSort() == Type.METHOD) {
                descriptor(type.getDescriptor(), sink);
            } else {
                type(type, sink);
            }
        } else if (value instanceof Handle) {
            Handle handle = (Handle) value;
            internal(handle.getOwner(), sink);
            descriptor(handle.getDesc(), sink);
        } else if (value instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) value;
            descriptor(dynamic.getDescriptor(), sink);
            constant(dynamic.getBootstrapMethod(), inputs, sink);
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                constant(dynamic.getBootstrapMethodArgument(i), inputs, sink);
            }
        }
    }

    private void string(String value, Map<String, ClassNode> inputs, Consumer<String> sink) {
        String candidate = value.replace('.', '/');
        if (inputs.containsKey(candidate)) {
            sink.accept(candidate);
        }
    }

    private void internal(String name, Consumer<String> sink) {
        if (name != null) {
            type(Type.getObjectType(name), sink);
        }
    }

    private void descriptor(String desc, Consumer<String> sink) {
        if (desc == null) {
            return;
        }
        Type parsed = Type.getType(desc);
        if (parsed.getSort() == Type.METHOD) {
            for (Type argument : parsed.getArgumentTypes()) {
                type(argument, sink);
            }
            type(parsed.getReturnType(), sink);
        } else {
            type(parsed, sink);
        }
    }

    private void type(Type type, Consumer<String> sink) {
        if (type.getSort() == Type.ARRAY) {
            type(type.getElementType(), sink);
        } else if (type.getSort() == Type.OBJECT) {
            sink.accept(type.getInternalName());
        }
    }

    private void annotations(List<AnnotationNode> annotations, Consumer<String> sink) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode annotation : annotations) {
            annotation(annotation, sink);
        }
    }

    private void parameterAnnotations(List<AnnotationNode>[] annotations, Consumer<String> sink) {
        if (annotations == null) {
            return;
        }
        for (List<AnnotationNode> list : annotations) {
            annotations(list, sink);
        }
    }

    private void annotation(AnnotationNode annotation, Consumer<String> sink) {
        descriptor(annotation.desc, sink);
        if (annotation.values != null) {
            for (Object value : annotation.values) {
                annotationValue(value, sink);
            }
        }
    }

    private void annotationValue(Object value, Consumer<String> sink) {
        if (value instanceof Type) {
            type((Type) value, sink);
        } else if (value instanceof String[]) {
            descriptor(((String[]) value)[0], sink);
        } else if (value instanceof AnnotationNode) {
            annotation((AnnotationNode) value, sink);
        } else if (value instanceof List) {
            for (Object element : (List<?>) value) {
                annotationValue(element, sink);
            }
        }
    }

    public static class Config extends TransformerConfig {
        private List<String> roots = new ArrayList<>();
        private boolean keepPublic = false;
        private boolean keepFrameworkClasses = true;

        public Config() {
            super(UnusedClassRemoverTransformer.class);
        }

        public List<String> getRoots() {
            return roots;
        }

        public void setRoots(List<String> roots) {
            this.roots = roots == null ? new ArrayList<>() : roots;
        }

        public boolean isKeepPublic() {
            return keepPublic;
        }

        public void setKeepPublic(boolean keepPublic) {
            this.keepPublic = keepPublic;
        }

        public boolean isKeepFrameworkClasses() {
            return keepFrameworkClasses;
        }

        public void setKeepFrameworkClasses(boolean keepFrameworkClasses) {
            this.keepFrameworkClasses = keepFrameworkClasses;
        }
    }
}
