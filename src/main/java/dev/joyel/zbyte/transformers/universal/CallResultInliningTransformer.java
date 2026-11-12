package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.analysis.AnalysisSupport;
import dev.joyel.zbyte.analysis.ClassEvaluator;
import dev.joyel.zbyte.analysis.ReAnalyzer;
import dev.joyel.zbyte.analysis.ReInterpreter;
import dev.joyel.zbyte.analysis.lookup.GetStaticLookup;
import dev.joyel.zbyte.analysis.value.DoubleValue;
import dev.joyel.zbyte.analysis.value.FloatValue;
import dev.joyel.zbyte.analysis.value.IntValue;
import dev.joyel.zbyte.analysis.value.LongValue;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.analysis.value.StringValue;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@TransformerConfig.ConfigOptions(configClass = CallResultInliningTransformer.Config.class)
public class CallResultInliningTransformer extends Transformer<CallResultInliningTransformer.Config> {

    private final Map<String, ClassEvaluator> evaluators = new HashMap<>();
    private final Set<String> failed = new HashSet<>();

    @Override
    public boolean transform() throws Throwable {
        evaluators.clear();
        failed.clear();
        AnalysisSupport support = new AnalysisSupport(classes, classpath);
        StaticValueCollectionTransformer collector = new StaticValueCollectionTransformer();
        collector.prepare(support);
        for (ClassNode classNode : new ArrayList<>(classNodes())) {
            collector.collect(classNode);
        }
        boolean changed = false;
        for (ClassNode node : new ArrayList<>(classNodes())) {
            for (MethodNode method : node.methods) {
                if (method.instructions == null || method.instructions.size() == 0) {
                    continue;
                }
                changed |= transformMethod(support, collector, node, method);
            }
        }
        return changed;
    }

    private boolean transformMethod(AnalysisSupport support, StaticValueCollectionTransformer collector, ClassNode node, MethodNode method) {
        boolean hasStaticCall = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                hasStaticCall = true;
                break;
            }
        }
        if (!hasStaticCall) {
            return false;
        }
        Frame<ReValue>[] frames;
        try {
            ReInterpreter interpreter = support.newInterpreter();
            installStaticValueCollector(interpreter, collector);
            frames = new ReAnalyzer(interpreter).analyze(node.name, method);
        } catch (Throwable ex) {
            return false;
        }
        boolean dirty = false;
        for (int i = method.instructions.size() - 1; i >= 0; i--) {
            AbstractInsnNode insn = method.instructions.get(i);
            if (insn.getOpcode() != Opcodes.INVOKESTATIC || !(insn instanceof MethodInsnNode min)) {
                continue;
            }
            Frame<ReValue> frame = i < frames.length ? frames[i] : null;
            if (frame == null) {
                continue;
            }
            Type methodType = Type.getMethodType(min.desc);
            int argumentCount = methodType.getArgumentTypes().length;
            List<ReValue> arguments = new ArrayList<>(argumentCount);
            for (int j = 0; j < argumentCount; j++) {
                arguments.add(0, frame.getStack(frame.getStackSize() - 1 - j));
            }
            if (!arguments.isEmpty() && arguments.stream().anyMatch(v -> !v.hasKnownValue())) {
                continue;
            }
            MethodNode target = findTarget(min);
            if (target == null || !canEvaluate(target)) {
                continue;
            }
            Object[] raw = toRawArguments(arguments);
            if (raw == null) {
                continue;
            }
            Object result;
            try {
                result = evaluatorFor(min.owner).execute(classes.get(min.owner), target, raw);
            } catch (Throwable ex) {
                continue;
            }
            AbstractInsnNode replacement = toInsn(result, methodType.getReturnType());
            if (replacement == null) {
                continue;
            }
            for (int arg = arguments.size() - 1; arg >= 0; arg--) {
                ReValue argValue = arguments.get(arg);
                if (argValue instanceof LongValue || argValue instanceof DoubleValue) {
                    method.instructions.insertBefore(min, new InsnNode(Opcodes.POP2));
                } else {
                    method.instructions.insertBefore(min, new InsnNode(Opcodes.POP));
                }
            }
            method.instructions.set(min, replacement);
            dirty = true;
        }
        return dirty;
    }

    private MethodNode findTarget(MethodInsnNode min) {
        String owner = min.owner;
        Set<String> visited = new HashSet<>();
        while (owner != null && visited.add(owner)) {
            ClassNode classNode = classes.get(owner);
            if (classNode == null) {
                return null;
            }
            for (MethodNode candidate : classNode.methods) {
                if (candidate.name.equals(min.name) && candidate.desc.equals(min.desc)) {
                    return (candidate.access & Opcodes.ACC_STATIC) != 0 ? candidate : null;
                }
            }
            owner = classNode.superName;
        }
        return null;
    }

    private static boolean canEvaluate(MethodNode target) {
        if ((target.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || target.instructions == null
                || target.instructions.size() == 0) {
            return false;
        }
        Type returnType = Type.getReturnType(target.desc);
        if (returnType.getSort() == Type.VOID || returnType.getSort() == Type.ARRAY) {
            return false;
        }
        return returnType.getSort() != Type.OBJECT || returnType.getInternalName().equals("java/lang/String");
    }

    private static Object[] toRawArguments(List<ReValue> arguments) {
        Object[] raw = new Object[arguments.size()];
        for (int i = 0; i < raw.length; i++) {
            ReValue value = arguments.get(i);
            if (value instanceof IntValue v) {
                raw[i] = v.value().getAsInt();
            } else if (value instanceof LongValue v) {
                raw[i] = v.value().getAsLong();
            } else if (value instanceof FloatValue v) {
                raw[i] = (float) v.value().getAsDouble();
            } else if (value instanceof DoubleValue v) {
                raw[i] = v.value().getAsDouble();
            } else if (value instanceof StringValue v && v.getText().isPresent()) {
                raw[i] = v.getText().get();
            } else {
                return null;
            }
        }
        return raw;
    }

    private static AbstractInsnNode toInsn(Object result, Type returnType) {
        if (result == null) {
            return null;
        }
        switch (returnType.getSort()) {
            case Type.BOOLEAN:
                return result instanceof Boolean b ? UniversalSupport.constantInsn(b ? 1 : 0)
                        : result instanceof Number n ? UniversalSupport.constantInsn(n.intValue() != 0 ? 1 : 0) : null;
            case Type.CHAR:
                return result instanceof Character c ? UniversalSupport.constantInsn((int) c)
                        : result instanceof Number n ? UniversalSupport.constantInsn(n.intValue() & 0xFFFF) : null;
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return result instanceof Number n ? UniversalSupport.constantInsn(n.intValue()) : null;
            case Type.LONG:
                return result instanceof Number n ? UniversalSupport.constantInsn(n.longValue()) : null;
            case Type.FLOAT:
                return result instanceof Number n ? UniversalSupport.constantInsn(n.floatValue()) : null;
            case Type.DOUBLE:
                return result instanceof Number n ? UniversalSupport.constantInsn(n.doubleValue()) : null;
            default:
                return result instanceof String s ? UniversalSupport.constantInsn(s) : null;
        }
    }

    private ClassEvaluator evaluatorFor(String owner) {
        return evaluators.computeIfAbsent(owner, name -> {
            ClassEvaluator evaluator = new ClassEvaluator(classes);
            if (getConfig().isEvaluateClassInitializers()) {
                evaluator.initialize(name);
            }
            return evaluator;
        });
    }

    private static void installStaticValueCollector(ReInterpreter interpreter, StaticValueCollectionTransformer collector) {
        GetStaticLookup fallback = interpreter.getGetStaticLookup();
        if (fallback == null) {
            interpreter.setGetStaticLookup(collector);
            return;
        }
        interpreter.setGetStaticLookup(new GetStaticLookup() {
            @Override
            public ReValue get(FieldInsnNode field) {
                return collector.hasLookup(field) ? collector.get(field) : fallback.get(field);
            }

            @Override
            public boolean hasLookup(FieldInsnNode field) {
                return collector.hasLookup(field) || fallback.hasLookup(field);
            }
        });
    }

    public static class Config extends TransformerConfig {

        private boolean evaluateClassInitializers = true;

        public Config() {
            super(CallResultInliningTransformer.class);
        }

        public boolean isEvaluateClassInitializers() {
            return evaluateClassInitializers;
        }

        public void setEvaluateClassInitializers(boolean evaluateClassInitializers) {
            this.evaluateClassInitializers = evaluateClassInitializers;
        }
    }
}
