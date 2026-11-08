package dev.joyel.zbyte;

import org.junit.Test;
import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.reflect.Method;
import java.util.*;

public class UniversalTransformersTest implements Opcodes {

    static void check(String name, boolean ok) {
        org.junit.Assert.assertTrue(name, ok);
    }

    static ClassNode cls(String name) {
        ClassNode c = new ClassNode();
        c.version = V1_8;
        c.access = ACC_PUBLIC | ACC_SUPER;
        c.name = name;
        c.superName = "java/lang/Object";
        return c;
    }

    static MethodNode method(ClassNode c, int access, String name, String desc, AbstractInsnNode... insns) {
        MethodNode m = new MethodNode(access, name, desc, null, null);
        for (AbstractInsnNode i : insns) m.instructions.add(i);
        m.maxStack = 8;
        m.maxLocals = 8;
        c.methods.add(m);
        return m;
    }

    static InsnNode in(int op) { return new InsnNode(op); }
    static IntInsnNode ii(int op, int v) { return new IntInsnNode(op, v); }
    static LdcInsnNode ldc(Object o) { return new LdcInsnNode(o); }
    static VarInsnNode var(int op, int v) { return new VarInsnNode(op, v); }

    static byte[] bytes(ClassNode c) {
        ClassNode copy = new ClassNode();
        c.accept(copy);
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        copy.accept(w);
        return w.toByteArray();
    }

    static class Loader extends ClassLoader {
        Class<?> define(String name, byte[] b) { return defineClass(name.replace('/', '.'), b, 0, b.length); }
    }

    static Object call(ClassNode c, String m, Class<?>[] types, Object... args) throws Exception {
        Class<?> k = new Loader().define(c.name, bytes(c));
        Method mm = k.getDeclaredMethod(m, types);
        mm.setAccessible(true);
        return mm.invoke(null, args);
    }

    static boolean run(Class<? extends Transformer> t, Map<String, ClassNode> classes) throws Throwable {
        Transformer<?> transformer = t.getDeclaredConstructor().newInstance();
        transformer.init(null, TransformerConfig.configFor(t), classes, classes, new HashMap<ClassNode, ClassReader>());
        return transformer.transform();
    }

    static Map<String, ClassNode> single(ClassNode c) {
        Map<String, ClassNode> m = new LinkedHashMap<>();
        m.put(c.name, c);
        return m;
    }

    static int count(MethodNode m, int op) {
        int n = 0;
        for (AbstractInsnNode i : m.instructions) if (i.getOpcode() == op) n++;
        return n;
    }

    static int real(MethodNode m) {
        int n = 0;
        for (AbstractInsnNode i : m.instructions) if (i.getOpcode() >= 0) n++;
        return n;
    }

    @Test
    public void numbers() throws Throwable {
        ClassNode c = cls("t/Num");
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                ii(BIPUSH, 5), ii(BIPUSH, 3), in(IADD), in(ICONST_2), in(IMUL), ii(BIPUSH, 4), in(INEG), in(ISUB), in(IRETURN));
        MethodNode l = method(c, ACC_PUBLIC | ACC_STATIC, "g", "()J",
                ldc(10L), ldc(3L), in(LMUL), ii(BIPUSH, 2), in(LSHL), in(LRETURN));
        MethodNode dv = method(c, ACC_PUBLIC | ACC_STATIC, "h", "()I",
                ii(BIPUSH, 5), in(ICONST_0), in(IDIV), in(IRETURN));
        Object before = call(c, "f", new Class<?>[0]);
        boolean changed = run(InlineConstantValuesTransformer.class, single(c));
        run(UselessPopCleanTransformer.class, single(c));
        check("numbers changed", changed);
        check("numbers int result", before.equals(call(c, "f", new Class<?>[0])) && ((Integer) before) == 20);
        check("numbers int collapsed", real(m) == 2);
        check("numbers long result", ((Long) call(c, "g", new Class<?>[0])) == 120L);
        check("numbers long collapsed", real(l) == 2);
        check("numbers div by zero untouched", count(dv, IDIV) == 1);
    }

    @Test
    public void jumps() throws Throwable {
        ClassNode c = cls("t/Jmp");
        LabelNode l = new LabelNode();
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                in(ICONST_3), in(ICONST_2), new JumpInsnNode(IF_ICMPLE, l), in(ICONST_1), in(IRETURN), l, in(ICONST_2), in(IRETURN));
        LabelNode l2 = new LabelNode();
        MethodNode m2 = method(c, ACC_PUBLIC | ACC_STATIC, "g", "()I",
                in(ICONST_0), new JumpInsnNode(IFNE, l2), in(ICONST_1), in(IRETURN), l2, in(ICONST_2), in(IRETURN));
        MethodNode m3 = method(c, ACC_PUBLIC | ACC_STATIC, "h", "(I)I",
                var(ILOAD, 0), new JumpInsnNode(IFNE, new LabelNode()), in(ICONST_1), in(IRETURN));
        LabelNode keep = new LabelNode();
        m3.instructions.clear();
        m3.instructions.add(var(ILOAD, 0));
        m3.instructions.add(new JumpInsnNode(IFNE, keep));
        m3.instructions.add(in(ICONST_1));
        m3.instructions.add(in(IRETURN));
        m3.instructions.add(keep);
        m3.instructions.add(in(ICONST_2));
        m3.instructions.add(in(IRETURN));
        boolean changed = run(CleanRedundantJumpsTransformer.class, single(c));
        check("jumps changed", changed);
        check("jumps taken result", ((Integer) call(c, "f", new Class<?>[0])) == 1 && count(m, IF_ICMPLE) == 0);
        check("jumps not taken result", ((Integer) call(c, "g", new Class<?>[0])) == 1 && count(m2, IFNE) == 0);
        check("jumps unknown preserved", count(m3, IFNE) == 1 && ((Integer) call(c, "h", new Class<?>[]{int.class}, 0)) == 1);
    }

    @Test
    public void switches() throws Throwable {
        ClassNode c = cls("t/Sw");
        LabelNode a = new LabelNode(), b = new LabelNode(), d = new LabelNode();
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                in(ICONST_1), new TableSwitchInsnNode(0, 2, d, a, b, a), a, in(ICONST_5), in(IRETURN), b, in(ICONST_4), in(IRETURN), d, in(ICONST_3), in(IRETURN));
        LabelNode x = new LabelNode(), y = new LabelNode();
        MethodNode m2 = method(c, ACC_PUBLIC | ACC_STATIC, "g", "()I",
                ii(BIPUSH, 9), new LookupSwitchInsnNode(y, new int[]{1, 2}, new LabelNode[]{x, x}), x, in(ICONST_5), in(IRETURN), y, in(ICONST_3), in(IRETURN));
        boolean changed = run(CleanRedundantSwitchesTransformer.class, single(c));
        check("switch changed", changed);
        check("switch table result", ((Integer) call(c, "f", new Class<?>[0])) == 4 && count(m, TABLESWITCH) == 0);
        check("switch lookup default", ((Integer) call(c, "g", new Class<?>[0])) == 3 && count(m2, LOOKUPSWITCH) == 0);
    }

    @Test
    public void arrays() throws Throwable {
        ClassNode c = cls("t/Arr");
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I", ii(BIPUSH, 7), ii(NEWARRAY, T_INT), in(ARRAYLENGTH), in(IRETURN));
        boolean changed = run(EmptyArrayLengthTransformer.class, single(c));
        check("arraylength folded", changed && real(m) == 2 && ((Integer) call(c, "f", new Class<?>[0])) == 7);
    }

    @Test
    public void classForName() throws Throwable {
        ClassNode c = cls("t/Cfn");
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                ldc("java.lang.Integer"), new MethodInsnNode(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false), in(POP),
                ldc("java.lang.String"), new MethodInsnNode(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false), in(POP),
                in(ICONST_1), in(IRETURN));
        boolean changed = run(ClassForNameTransformer.class, single(c));
        check("classforname removes configured only", changed && count(m, INVOKESTATIC) == 1);
    }

    @Test
    public void stringBuilder() throws Throwable {
        ClassNode c = cls("t/Sb");
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()Ljava/lang/String;",
                new TypeInsnNode(NEW, "java/lang/StringBuilder"), in(DUP), new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false),
                ldc("ab"), new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false),
                in(ICONST_1), new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false),
                ii(BIPUSH, 99), new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false),
                new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false), in(ARETURN));
        MethodNode m2 = method(c, ACC_PUBLIC | ACC_STATIC, "g", "(Ljava/lang/String;)Ljava/lang/String;",
                new TypeInsnNode(NEW, "java/lang/StringBuilder"), in(DUP), new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false),
                var(ALOAD, 0), new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false),
                new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false), in(ARETURN));
        boolean changed = run(StringBuilderTransformer.class, single(c));
        check("stringbuilder folded", changed && "ab1c".equals(call(c, "f", new Class<?>[0])) && real(m) == 2);
        check("stringbuilder dynamic kept", count(m2, NEW) == 1 && "x".equals(call(c, "g", new Class<?>[]{String.class}, "x")));
    }

    @Test
    public void access() throws Throwable {
        ClassNode c = cls("t/Acc");
        c.access |= ACC_VOLATILE | ACC_NATIVE;
        FieldNode f = new FieldNode(ACC_PUBLIC | ACC_ENUM | ACC_NATIVE, "x", "I", null, null);
        c.fields.add(f);
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED | ACC_INTERFACE | ACC_ENUM, "f", "()V", in(RETURN));
        boolean changed = run(AccessRepairTransformer.class, single(c));
        check("access repaired", changed && (f.access & (ACC_ENUM | ACC_NATIVE)) == 0
                && (m.access & (ACC_INTERFACE | ACC_ENUM)) == 0 && (m.access & ACC_SYNCHRONIZED) != 0
                && (c.access & ACC_SUPER) != 0 && (c.access & ACC_NATIVE) == 0);
    }

    @Test
    public void tryCatch() throws Throwable {
        ClassNode c = cls("t/Tc");
        LabelNode s = new LabelNode(), e = new LabelNode(), h = new LabelNode();
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I", s, in(ICONST_1), e, in(IRETURN), h, in(POP), in(ICONST_2), in(IRETURN));
        m.tryCatchBlocks.add(new TryCatchBlockNode(s, e, h, "java/lang/Exception"));
        m.tryCatchBlocks.add(new TryCatchBlockNode(e, s, h, "java/lang/Exception"));
        m.tryCatchBlocks.add(new TryCatchBlockNode(s, new LabelNode(), h, "java/lang/Exception"));
        boolean changed = run(TryCatchRepairTransformer.class, single(c));
        check("trycatch repaired", changed && m.tryCatchBlocks.size() == 1);
    }

    @Test
    public void synthetics() throws Throwable {
        ClassNode c = cls("t/Syn");
        MethodNode m = method(c, ACC_PRIVATE | ACC_STATIC, "lambda$main$0", "()V", in(RETURN));
        MethodNode n = method(c, ACC_PRIVATE | ACC_STATIC, "other", "()V", in(RETURN));
        boolean changed = run(RecoverSyntheticsTransformer.class, single(c));
        check("synthetics recovered", changed && (m.access & ACC_SYNTHETIC) != 0 && (n.access & ACC_SYNTHETIC) == 0);
    }

    @Test
    public void annotations() throws Throwable {
        ClassNode c = cls("t/Ann");
        c.visibleAnnotations = new ArrayList<>(Arrays.asList(new AnnotationNode("bad"), new AnnotationNode("Ljava/lang/Deprecated;")));
        MethodNode m = method(c, ACC_PUBLIC | ACC_STATIC, "f", "(I)V", in(RETURN));
        @SuppressWarnings("unchecked")
        List<AnnotationNode>[] params = new List[]{new ArrayList<>(Arrays.asList(new AnnotationNode("L;")))};
        m.visibleParameterAnnotations = params;
        boolean changed = run(AnnotationFilterTransformer.class, single(c));
        check("annotations filtered", changed && c.visibleAnnotations.size() == 1 && m.visibleParameterAnnotations[0].isEmpty());
    }

    @Test
    public void pure() throws Throwable {
        ClassNode c = cls("t/Pure");
        method(c, ACC_PRIVATE | ACC_STATIC, "add", "(IJ)J", var(ILOAD, 0), in(I2L), var(LLOAD, 1), in(LADD), in(LRETURN));
        MethodNode caller = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()J",
                ii(BIPUSH, 3), ldc(40L), new MethodInsnNode(INVOKESTATIC, "t/Pure", "add", "(IJ)J", false), in(LRETURN));
        Object before = ((Long) 43L);
        boolean changed = run(InlinePureFunctionsTransformer.class, single(c));
        check("pure inlined", changed && count(caller, INVOKESTATIC) == 0);
        check("pure result", before.equals(call(c, "f", new Class<?>[0])));
        check("pure method removed", c.methods.size() == 1);
    }

    @Test
    public void stringPool() throws Throwable {
        ClassNode c = cls("t/Sp");
        c.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "pool", "[Ljava/lang/String;", null, null));
        method(c, ACC_PRIVATE | ACC_STATIC, "init", "()V",
                in(ICONST_2), new TypeInsnNode(ANEWARRAY, "java/lang/String"), new FieldInsnNode(PUTSTATIC, "t/Sp", "pool", "[Ljava/lang/String;"),
                new FieldInsnNode(GETSTATIC, "t/Sp", "pool", "[Ljava/lang/String;"), in(ICONST_0), ldc("hello"), in(AASTORE),
                new FieldInsnNode(GETSTATIC, "t/Sp", "pool", "[Ljava/lang/String;"), in(ICONST_1), ldc("world"), in(AASTORE), in(RETURN));
        method(c, ACC_STATIC, "<clinit>", "()V", new MethodInsnNode(INVOKESTATIC, "t/Sp", "init", "()V", false), in(RETURN));
        MethodNode f = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()Ljava/lang/String;",
                new FieldInsnNode(GETSTATIC, "t/Sp", "pool", "[Ljava/lang/String;"), in(ICONST_1), in(AALOAD), in(ARETURN));
        boolean changed = run(UniversalStringPoolTransformer.class, single(c));
        check("string pool replaced", changed && "world".equals(call(c, "f", new Class<?>[0])) && real(f) == 2);
        check("string pool removed", c.fields.isEmpty() && c.methods.size() == 2);
    }

    @Test
    public void numberPool() throws Throwable {
        ClassNode c = cls("t/Np");
        c.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "pool", "[I", null, null));
        method(c, ACC_PRIVATE | ACC_STATIC, "init", "()V",
                in(ICONST_2), ii(NEWARRAY, T_INT), in(DUP), in(ICONST_0), ii(SIPUSH, 1000), in(IASTORE), in(DUP), in(ICONST_1), ldc(70000), in(IASTORE),
                new FieldInsnNode(PUTSTATIC, "t/Np", "pool", "[I"), in(RETURN));
        method(c, ACC_STATIC, "<clinit>", "()V", new MethodInsnNode(INVOKESTATIC, "t/Np", "init", "()V", false), in(RETURN));
        MethodNode f = method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                new FieldInsnNode(GETSTATIC, "t/Np", "pool", "[I"), in(ICONST_1), in(IALOAD), in(IRETURN));
        boolean changed = run(UniversalNumberPoolTransformer.class, single(c));
        check("number pool replaced", changed && ((Integer) call(c, "f", new Class<?>[0])) == 70000 && real(f) == 2);
        check("number pool removed", c.fields.isEmpty() && c.methods.size() == 2);

        ClassNode b = cls("t/Bp");
        b.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "pool", "[Ljava/lang/Long;", null, null));
        method(b, ACC_PRIVATE | ACC_STATIC, "init", "()V",
                in(ICONST_1), new TypeInsnNode(ANEWARRAY, "java/lang/Long"), new FieldInsnNode(PUTSTATIC, "t/Bp", "pool", "[Ljava/lang/Long;"),
                new FieldInsnNode(GETSTATIC, "t/Bp", "pool", "[Ljava/lang/Long;"), in(ICONST_0), ldc(123456789012L),
                new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false), in(AASTORE), in(RETURN));
        method(b, ACC_STATIC, "<clinit>", "()V", new MethodInsnNode(INVOKESTATIC, "t/Bp", "init", "()V", false), in(RETURN));
        MethodNode g = method(b, ACC_PUBLIC | ACC_STATIC, "f", "()J",
                new FieldInsnNode(GETSTATIC, "t/Bp", "pool", "[Ljava/lang/Long;"), in(ICONST_0), in(AALOAD),
                new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false), in(LRETURN));
        boolean changed2 = run(UniversalNumberPoolTransformer.class, single(b));
        check("boxed pool replaced", changed2 && ((Long) call(b, "f", new Class<?>[0])) == 123456789012L && real(g) == 2);
    }
}
