package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.analysis.ClassEvaluator;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InvokeDynamicFrameworkTest implements Opcodes {

    public static class AbsResolver implements InvokeDynamicResolver {
        @Override
        public ResolvedInvokeDynamic resolve(IndyContext context, ClassNode classNode, MethodNode method,
                                             InvokeDynamicInsnNode instruction, Frame<ReValue> frame) {
            if (!instruction.name.equals("absolute")) {
                return null;
            }
            return new ResolvedInvokeDynamic(new Handle(H_INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false), 0);
        }
    }

    public static class FirstResolver implements InvokeDynamicResolver {
        @Override
        public int getPriority() {
            return -5;
        }

        @Override
        public ResolvedInvokeDynamic resolve(IndyContext context, ClassNode classNode, MethodNode method,
                                             InvokeDynamicInsnNode instruction, Frame<ReValue> frame) {
            return null;
        }
    }

    private static ClassNode cls(String name) {
        ClassNode c = new ClassNode();
        c.version = V1_8;
        c.access = ACC_PUBLIC | ACC_SUPER;
        c.name = name;
        c.superName = "java/lang/Object";
        return c;
    }

    private static MethodNode method(ClassNode c, int access, String name, String desc, AbstractInsnNode... insns) {
        MethodNode m = new MethodNode(access, name, desc, null, null);
        for (AbstractInsnNode i : insns) {
            m.instructions.add(i);
        }
        m.maxStack = 8;
        m.maxLocals = 8;
        c.methods.add(m);
        return m;
    }

    private static Map<String, ClassNode> classes(ClassNode... nodes) {
        Map<String, ClassNode> map = new LinkedHashMap<>();
        for (ClassNode n : nodes) {
            map.put(n.name, n);
        }
        return map;
    }

    private static boolean run(Class<? extends Transformer> type, Map<String, ClassNode> classes) throws Throwable {
        Transformer<?> transformer = type.getDeclaredConstructor().newInstance();
        transformer.init(null, TransformerConfig.configFor(type), classes, classes, new HashMap<ClassNode, ClassReader>());
        return transformer.transform();
    }

    @Test
    public void resolversAreSortedByPriority() {
        List<InvokeDynamicResolver> sorted = InvokeDynamicResolverManager.sort(List.of(new AbsResolver(), new FirstResolver()));
        assertTrue(sorted.get(0) instanceof FirstResolver);
    }

    @Test
    public void registeredResolverInlinesCallSite() throws Throwable {
        InvokeDynamicResolverManager.register(AbsResolver.class);
        try {
            ClassNode c = cls("i/Site");
            Handle bsm = new Handle(H_INVOKESTATIC, "i/Site", "bootstrap", "()V", false);
            MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "(I)I",
                    new VarInsnNode(ILOAD, 0), new InvokeDynamicInsnNode("absolute", "(I)I", bsm), new InsnNode(IRETURN));
            assertTrue(run(InvokeDynamicInliningTransformer.class, classes(c)));
            int indy = 0;
            int calls = 0;
            for (AbstractInsnNode insn : c.methods.get(0).instructions) {
                if (insn instanceof InvokeDynamicInsnNode) {
                    indy++;
                }
                if (insn.getOpcode() == INVOKESTATIC) {
                    calls++;
                }
            }
            assertEquals(0, indy);
            assertEquals(1, calls);
        } finally {
            InvokeDynamicResolverManager.unregister(AbsResolver.class);
        }
    }

    @Test
    public void unmatchedCallSiteIsKept() throws Throwable {
        ClassNode c = cls("i/Keep");
        Handle bsm = new Handle(H_INVOKESTATIC, "i/Keep", "bootstrap", "()V", false);
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "(I)I",
                new VarInsnNode(ILOAD, 0), new InvokeDynamicInsnNode("absolute", "(I)I", bsm), new InsnNode(IRETURN));
        assertFalse(run(InvokeDynamicInliningTransformer.class, classes(c)));
    }

    @Test
    public void evaluatorRunsStaticMethodsAndInitializers() throws Throwable {
        ClassNode c = cls("e/Eval");
        c.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PRIVATE | ACC_STATIC, "X", "I", null, null));
        method(c, ACC_STATIC, "<clinit>", "()V", new IntInsnNode(BIPUSH, 5), new FieldInsnNode(PUTSTATIC, "e/Eval", "X", "I"), new InsnNode(RETURN));
        method(c, ACC_PUBLIC | ACC_STATIC, "mul", "(II)I",
                new VarInsnNode(ILOAD, 0), new VarInsnNode(ILOAD, 1), new InsnNode(IMUL), new InsnNode(ICONST_1), new InsnNode(IADD), new InsnNode(IRETURN));
        method(c, ACC_PUBLIC | ACC_STATIC, "x", "()I", new FieldInsnNode(GETSTATIC, "e/Eval", "X", "I"), new InsnNode(IRETURN));
        ClassEvaluator evaluator = new ClassEvaluator(classes(c));
        assertEquals(13, ((Number) evaluator.invokeStatic("e/Eval", "mul", "(II)I", 3, 4)).intValue());
        assertTrue(evaluator.initialize("e/Eval"));
        assertEquals(5, ((Number) evaluator.invokeStatic("e/Eval", "x", "()I")).intValue());
        List<String> unused = new ArrayList<>();
        assertTrue(unused.isEmpty());
    }
}
