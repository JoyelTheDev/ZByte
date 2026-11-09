package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnalysisTransformersTest implements Opcodes {

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

    private static InsnNode in(int op) {
        return new InsnNode(op);
    }

    private static IntInsnNode ii(int op, int v) {
        return new IntInsnNode(op, v);
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

    private static int count(MethodNode m, int op) {
        int n = 0;
        for (AbstractInsnNode i : m.instructions) {
            if (i.getOpcode() == op) {
                n++;
            }
        }
        return n;
    }

    private static int real(MethodNode m) {
        int n = 0;
        for (AbstractInsnNode i : m.instructions) {
            if (i.getOpcode() >= 0) {
                n++;
            }
        }
        return n;
    }

    private static class Loader extends ClassLoader {
        Class<?> define(String name, byte[] b) {
            return defineClass(name.replace('/', '.'), b, 0, b.length);
        }
    }

    private static Object call(ClassNode c, String name) throws Exception {
        ClassNode copy = new ClassNode();
        c.accept(copy);
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        copy.accept(w);
        Class<?> k = new Loader().define(c.name, w.toByteArray());
        Method m = k.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }

    @Test
    public void opaqueConstantFolding() throws Throwable {
        ClassNode c = cls("a/Opaque");
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                ii(BIPUSH, 5), ii(BIPUSH, 3), in(IADD), in(ICONST_2), in(IMUL), in(IRETURN));
        assertTrue(run(OpaqueConstantFoldingTransformer.class, classes(c)));
        assertEquals(16, call(c, "f"));
        assertTrue(real(m) <= 3);
    }

    @Test
    public void opaquePredicateFolding() throws Throwable {
        ClassNode c = cls("a/Predicate");
        LabelNode l = new LabelNode();
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                in(ICONST_3), in(ICONST_2), new JumpInsnNode(IF_ICMPLE, l), in(ICONST_1), in(IRETURN), l, in(ICONST_2), in(IRETURN));
        assertTrue(run(OpaquePredicateFoldingTransformer.class, classes(c)));
        assertEquals(1, call(c, "f"));
        assertEquals(0, count(c.methods.get(0), IF_ICMPLE));
    }

    @Test
    public void variableFolding() throws Throwable {
        ClassNode c = cls("a/Variables");
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                ii(BIPUSH, 5), new VarInsnNode(ISTORE, 0), new VarInsnNode(ILOAD, 0), ii(BIPUSH, 3), in(IADD), in(IRETURN));
        assertTrue(run(VariableFoldingTransformer.class, classes(c)));
        assertEquals(8, call(c, "f"));
        assertEquals(0, count(c.methods.get(0), ILOAD));
    }

    @Test
    public void staticValueInlining() throws Throwable {
        ClassNode c = cls("a/Static");
        c.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "X", "I", null, null));
        method(c, ACC_STATIC, "<clinit>", "()V", ii(BIPUSH, 7), new FieldInsnNode(PUTSTATIC, "a/Static", "X", "I"), in(RETURN));
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I", new FieldInsnNode(GETSTATIC, "a/Static", "X", "I"), in(IRETURN));
        assertTrue(run(StaticValueInliningTransformer.class, classes(c)));
        MethodNode f = null;
        for (MethodNode m : c.methods) {
            if (m.name.equals("f")) {
                f = m;
            }
        }
        assertEquals(0, count(f, GETSTATIC));
        assertEquals(7, call(c, "f"));
    }

    @Test
    public void redundantTryCatch() throws Throwable {
        ClassNode c = cls("a/Try");
        LabelNode s = new LabelNode(), e = new LabelNode(), h = new LabelNode();
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                s, in(ICONST_1), in(POP), e, in(ICONST_1), in(IRETURN), h, in(POP), in(ICONST_2), in(IRETURN));
        m.tryCatchBlocks.add(new TryCatchBlockNode(s, e, h, "java/lang/Exception"));
        assertTrue(run(RedundantTryCatchRemovingTransformer.class, classes(c)));
        assertTrue(c.methods.get(0).tryCatchBlocks.isEmpty());
        assertEquals(1, call(c, "f"));
    }

    @Test
    public void exceptionCollection() throws Throwable {
        ClassNode c = cls("a/Thrower");
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "()V",
                new TypeInsnNode(NEW, "java/io/IOException"), in(DUP),
                new MethodInsnNode(INVOKESPECIAL, "java/io/IOException", "<init>", "()V", false), in(ATHROW));
        Set<String> thrown = ExceptionCollectionTransformer.collect(classes(c), new HashMap<>());
        assertTrue(thrown.contains("java/io/IOException"));
        assertTrue(thrown.contains("java/lang/Exception"));
    }

    @Test
    public void reflectionResolver() throws Throwable {
        ClassNode target = cls("a/Target");
        method(target, ACC_PUBLIC | ACC_STATIC, "val", "()I", in(ICONST_5), in(IRETURN));
        ClassNode c = cls("a/Caller");
        MethodNode forName = method(c, ACC_PUBLIC | ACC_STATIC, "g", "()Ljava/lang/Class;",
                new LdcInsnNode("a.Target"), new MethodInsnNode(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false), in(ARETURN));
        MethodNode invoke = method(c, ACC_PUBLIC | ACC_STATIC, "h", "()Ljava/lang/Object;",
                new LdcInsnNode(Type.getObjectType("a/Target")), new LdcInsnNode("val"), in(ICONST_0), new TypeInsnNode(ANEWARRAY, "java/lang/Class"),
                new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false),
                new InsnNode(ACONST_NULL), in(ICONST_0), new TypeInsnNode(ANEWARRAY, "java/lang/Object"),
                new MethodInsnNode(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false), in(ARETURN));
        assertTrue(run(ReflectionResolverTransformer.class, classes(target, c)));
        assertEquals(0, count(forName, INVOKESTATIC));
        boolean direct = false;
        for (AbstractInsnNode insn : invoke.instructions) {
            if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).owner.equals("a/Target") && ((MethodInsnNode) insn).name.equals("val")) {
                direct = true;
            }
        }
        assertTrue(direct);
    }
}
