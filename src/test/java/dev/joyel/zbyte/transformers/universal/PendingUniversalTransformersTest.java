package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.transformers.normalizer.CustomRemapper;
import org.junit.Test;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PendingUniversalTransformersTest implements Opcodes {

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

    private static Map<String, ClassNode> single(ClassNode... nodes) {
        Map<String, ClassNode> map = new LinkedHashMap<>();
        for (ClassNode n : nodes) {
            map.put(n.name, n);
        }
        return map;
    }

    private static Transformer<?> init(Class<? extends Transformer> type, TransformerConfig config, Map<String, ClassNode> classes) throws Exception {
        Transformer<?> transformer = type.getDeclaredConstructor().newInstance();
        transformer.init(null, config, classes, classes, new HashMap<ClassNode, ClassReader>());
        return transformer;
    }

    private static boolean run(Class<? extends Transformer> type, Map<String, ClassNode> classes) throws Throwable {
        return init(type, TransformerConfig.configFor(type), classes).transform();
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

    private static int count(MethodNode m, int op) {
        int n = 0;
        for (AbstractInsnNode i : m.instructions) {
            if (i.getOpcode() == op) {
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
    public void duplicateAnnotations() throws Throwable {
        ClassNode c = cls("p/Dup");
        c.visibleAnnotations = new ArrayList<>(Arrays.asList(new AnnotationNode("Lp/A;"), new AnnotationNode("Lp/A;"), new AnnotationNode("Lp/B;")));
        assertTrue(run(DuplicateAnnotationRemovingTransformer.class, single(c)));
        assertEquals(2, c.visibleAnnotations.size());
        assertFalse(run(DuplicateAnnotationRemovingTransformer.class, single(c)));
    }

    @Test
    public void frameRemoval() throws Throwable {
        ClassNode c = cls("p/Fr");
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I", in(ICONST_1), new FrameNode(F_SAME, 0, null, 0, null), in(IRETURN));
        assertTrue(run(FrameRemovingTransformer.class, single(c)));
        assertEquals(0, m.instructions.toArray().length - real(m));
    }

    @Test
    public void longAnnotationsAndExceptions() throws Throwable {
        ClassNode c = cls("p/Long");
        c.visibleAnnotations = new ArrayList<>(Arrays.asList(new AnnotationNode("Lp/ThisIsAVeryLongAnnotationName;"), new AnnotationNode("Lp/A;")));
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()V", in(RETURN));
        m.exceptions = new ArrayList<>(Arrays.asList("p/ThisIsAVeryLongExceptionName", "p/E"));
        LongAnnotationRemovingTransformer.Config annotationConfig = new LongAnnotationRemovingTransformer.Config();
        annotationConfig.setMaxLength(20);
        LongExceptionRemovingTransformer.Config exceptionConfig = new LongExceptionRemovingTransformer.Config();
        exceptionConfig.setMaxLength(20);
        assertTrue(init(LongAnnotationRemovingTransformer.class, annotationConfig, single(c)).transform());
        assertTrue(init(LongExceptionRemovingTransformer.class, exceptionConfig, single(c)).transform());
        assertEquals(1, c.visibleAnnotations.size());
        assertEquals(1, m.exceptions.size());
    }

    @Test
    public void syntheticModifiers() throws Throwable {
        ClassNode c = cls("p/Syn");
        c.access |= ACC_SYNTHETIC;
        FieldNode f = new FieldNode(ACC_PRIVATE | ACC_SYNTHETIC, "x", "I", null, null);
        c.fields.add(f);
        MethodNode m = method(c, ACC_PUBLIC | ACC_SYNTHETIC | ACC_BRIDGE, "f", "()V", in(RETURN));
        assertTrue(run(SyntheticModifierRemovingTransformer.class, single(c)));
        assertEquals(0, (c.access | f.access | m.access) & (ACC_SYNTHETIC | ACC_BRIDGE));
    }

    @Test
    public void unknownAttributes() throws Throwable {
        ClassNode c = cls("p/Attr");
        c.attrs = new ArrayList<>(Arrays.asList(new Attribute("Junk") {
        }));
        assertTrue(run(UnknownAttributeRemovingTransformer.class, single(c)));
        assertTrue(c.attrs.isEmpty());
    }

    @Test
    public void deadCode() throws Throwable {
        ClassNode c = cls("p/Dead");
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I", in(ICONST_1), in(IRETURN), in(ICONST_2), in(IRETURN));
        assertTrue(run(DeadCodeRemovingTransformer.class, single(c)));
        assertEquals(2, real(m));
        assertEquals(1, call(c, "f"));
    }

    @Test
    public void gotoInlining() throws Throwable {
        ClassNode c = cls("p/Goto");
        LabelNode target = new LabelNode();
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                new JumpInsnNode(GOTO, target), in(ICONST_2), in(IRETURN), target, in(ICONST_1), in(IRETURN));
        assertTrue(run(GotoInliningTransformer.class, single(c)));
        assertEquals(0, count(c.methods.get(0), GOTO));
        assertEquals(1, call(c, "f"));
    }

    @Test
    public void variableTable() throws Throwable {
        ClassNode c = cls("p/Var");
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                in(ICONST_1), new VarInsnNode(ISTORE, 5), new VarInsnNode(ILOAD, 5), in(IRETURN));
        assertTrue(run(VariableTableNormalizingTransformer.class, single(c)));
        assertEquals(1, m.maxLocals);
        assertEquals(0, ((VarInsnNode) m.instructions.get(2)).var == 0 ? 0 : 1);
        assertEquals(1, call(c, "f"));
    }

    @Test
    public void duplicateCatchMerging() throws Throwable {
        ClassNode c = cls("p/Catch");
        LabelNode s1 = new LabelNode(), e1 = new LabelNode(), s2 = new LabelNode(), e2 = new LabelNode();
        LabelNode h1 = new LabelNode(), h2 = new LabelNode();
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                s1, in(ICONST_1), in(POP), e1, new JumpInsnNode(GOTO, s2),
                h1, new VarInsnNode(ASTORE, 1), new IntInsnNode(BIPUSH, 7), new VarInsnNode(ISTORE, 2), new VarInsnNode(ILOAD, 2), in(IRETURN),
                s2, in(ICONST_2), in(POP), e2, in(ICONST_3), in(IRETURN),
                h2, new VarInsnNode(ASTORE, 1), new IntInsnNode(BIPUSH, 7), new VarInsnNode(ISTORE, 2), new VarInsnNode(ILOAD, 2), in(IRETURN));
        m.tryCatchBlocks.add(new TryCatchBlockNode(s1, e1, h1, "java/lang/Exception"));
        m.tryCatchBlocks.add(new TryCatchBlockNode(s2, e2, h2, "java/lang/Exception"));
        assertTrue(run(DuplicateCatchMergingTransformer.class, single(c)));
        assertEquals(1, count(m, ISTORE));
        assertEquals(3, call(c, "f"));
    }

    @Test
    public void cycleClasses() throws Throwable {
        ClassNode a = cls("p/A");
        a.superName = "p/B";
        ClassNode b = cls("p/B");
        b.superName = "p/A";
        ClassNode ok = cls("p/Ok");
        Map<String, ClassNode> classes = single(a, b, ok);
        assertTrue(run(CycleClassRemovingTransformer.class, classes));
        assertEquals(1, classes.size());
        assertTrue(classes.containsKey("p/Ok"));
    }

    @Test
    public void unusedMethods() throws Throwable {
        ClassNode c = cls("p/Unused");
        c.access = ACC_SUPER;
        method(c, ACC_PRIVATE | ACC_STATIC, "dead", "()I", in(ICONST_1), in(IRETURN));
        method(c, ACC_PRIVATE | ACC_STATIC, "live", "()I", in(ICONST_2), in(IRETURN));
        method(c, ACC_STATIC, "entry", "()I", new MethodInsnNode(INVOKESTATIC, "p/Unused", "live", "()I", false), in(IRETURN));
        method(c, ACC_PUBLIC, "toString", "()Ljava/lang/String;", new LdcInsnNode("x"), in(ARETURN));
        assertTrue(run(UnusedMethodRemoverTransformer.class, single(c)));
        List<String> names = new ArrayList<>();
        for (MethodNode m : c.methods) {
            names.add(m.name);
        }
        assertFalse(names.contains("dead"));
        assertTrue(names.contains("live") && names.contains("toString"));
    }

    @Test
    public void enumNames() throws Throwable {
        ClassNode c = cls("p/E");
        c.access |= ACC_ENUM;
        c.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, "a", "Lp/E;", null, null));
        method(c, ACC_STATIC, "<clinit>", "()V",
                new TypeInsnNode(NEW, "p/E"), in(DUP), new LdcInsnNode("RED"), in(ICONST_0),
                new MethodInsnNode(INVOKESPECIAL, "p/E", "<init>", "(Ljava/lang/String;I)V", false),
                new FieldInsnNode(PUTSTATIC, "p/E", "a", "Lp/E;"), in(RETURN));
        EnumNameRestorationTransformer transformer = (EnumNameRestorationTransformer) init(EnumNameRestorationTransformer.class, TransformerConfig.configFor(EnumNameRestorationTransformer.class), single(c));
        CustomRemapper remapper = new CustomRemapper();
        transformer.remap(remapper);
        assertEquals("RED", remapper.mapFieldName("p/E", "a", "Lp/E;"));
    }
}
