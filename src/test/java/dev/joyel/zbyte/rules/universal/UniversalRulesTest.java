package dev.joyel.zbyte.rules.universal;

import dev.joyel.zbyte.ZByte;
import dev.joyel.zbyte.config.Configuration;
import dev.joyel.zbyte.rules.Rule;
import dev.joyel.zbyte.rules.Rules;
import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class UniversalRulesTest implements Opcodes {

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

    private static Deobfuscator deobfuscator(ClassNode... classes) {
        Deobfuscator d = new Deobfuscator(new Configuration());
        for (ClassNode c : classes) {
            d.getClasses().put(c.name, c);
        }
        return d;
    }

    private static String run(Class<? extends Rule> type, Deobfuscator d) throws Exception {
        return type.getDeclaredConstructor().newInstance().test(d);
    }

    private static ClassNode clean() {
        ClassNode c = cls("t/Clean");
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "(I)I", new InsnNode(ICONST_1), new InsnNode(IRETURN));
        return c;
    }

    @Test
    public void cleanInputTriggersNothing() throws Exception {
        Deobfuscator d = deobfuscator(clean());
        for (Rule rule : Rules.RULES) {
            if (rule.getClass().getPackage().getName().endsWith("universal")) {
                assertNull(rule.getClass().getSimpleName(), rule.test(d));
            }
        }
    }

    @Test
    public void illegalAccessFlags() throws Exception {
        ClassNode c = clean();
        c.fields.add(new FieldNode(ACC_PUBLIC | ACC_NATIVE, "x", "I", null, null));
        assertNotNull(run(RuleIllegalAccessFlags.class, deobfuscator(c)));
    }

    @Test
    public void constantArithmeticAndJumps() throws Exception {
        ClassNode c = cls("t/Flow");
        LabelNode l = new LabelNode();
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                new InsnNode(ICONST_3), new InsnNode(ICONST_2), new JumpInsnNode(IF_ICMPLE, l), new InsnNode(ICONST_1), new InsnNode(IRETURN),
                l, new IntInsnNode(BIPUSH, 5), new IntInsnNode(BIPUSH, 3), new InsnNode(IADD), new InsnNode(IRETURN));
        Deobfuscator d = deobfuscator(c);
        assertNotNull(run(RuleRedundantJumps.class, d));
        assertNotNull(run(RuleConstantArithmetic.class, d));
        assertNull(run(RuleRedundantSwitches.class, d));
    }

    @Test
    public void stringBuilderConstants() throws Exception {
        ClassNode c = cls("t/Sb");
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "()Ljava/lang/String;",
                new TypeInsnNode(NEW, "java/lang/StringBuilder"), new InsnNode(DUP),
                new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false),
                new LdcInsnNode("ab"), new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false),
                new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false), new InsnNode(ARETURN));
        assertNotNull(run(RuleStringBuilderConstants.class, deobfuscator(c)));
    }

    @Test
    public void stringPool() throws Exception {
        ClassNode c = cls("t/Sp");
        c.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "pool", "[Ljava/lang/String;", null, null));
        method(c, ACC_PRIVATE | ACC_STATIC, "init", "()V",
                new InsnNode(ICONST_1), new TypeInsnNode(ANEWARRAY, "java/lang/String"), new FieldInsnNode(PUTSTATIC, "t/Sp", "pool", "[Ljava/lang/String;"),
                new FieldInsnNode(GETSTATIC, "t/Sp", "pool", "[Ljava/lang/String;"), new InsnNode(ICONST_0), new LdcInsnNode("a"), new InsnNode(AASTORE), new InsnNode(RETURN));
        method(c, ACC_STATIC, "<clinit>", "()V", new MethodInsnNode(INVOKESTATIC, "t/Sp", "init", "()V", false), new InsnNode(RETURN));
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "()Ljava/lang/String;",
                new FieldInsnNode(GETSTATIC, "t/Sp", "pool", "[Ljava/lang/String;"), new InsnNode(ICONST_0), new InsnNode(AALOAD), new InsnNode(ARETURN));
        assertNotNull(run(RuleStringPool.class, deobfuscator(c)));
    }

    @Test
    public void deadCodeAndCycles() throws Exception {
        ClassNode c = cls("t/Dead");
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I", new InsnNode(ICONST_1), new InsnNode(IRETURN), new InsnNode(ICONST_2), new InsnNode(IRETURN));
        assertNotNull(run(RuleDeadCode.class, deobfuscator(c)));
        ClassNode a = cls("t/A");
        a.superName = "t/B";
        ClassNode b = cls("t/B");
        b.superName = "t/A";
        assertNotNull(run(RuleCycleClasses.class, deobfuscator(a, b)));
        assertNull(run(RuleCycleClasses.class, deobfuscator(clean())));
    }

    @Test
    public void opaquePredicatesAndRedundantTryCatch() throws Exception {
        ClassNode c = cls("t/Opaque");
        LabelNode l = new LabelNode();
        method(c, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                new InsnNode(ICONST_3), new InsnNode(ICONST_2), new JumpInsnNode(IF_ICMPLE, l), new InsnNode(ICONST_1), new InsnNode(IRETURN),
                l, new InsnNode(ICONST_2), new InsnNode(IRETURN));
        assertNotNull(run(RuleOpaquePredicates.class, deobfuscator(c)));
        ClassNode t = cls("t/Try");
        LabelNode s = new LabelNode(), e = new LabelNode(), h = new LabelNode();
        MethodNode m = method(t, ACC_PUBLIC | ACC_STATIC, "f", "()I",
                s, new InsnNode(ICONST_1), new InsnNode(POP), e, new InsnNode(ICONST_1), new InsnNode(IRETURN), h, new InsnNode(POP), new InsnNode(ICONST_2), new InsnNode(IRETURN));
        m.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(s, e, h, "java/lang/Exception"));
        assertNotNull(run(RuleRedundantTryCatch.class, deobfuscator(t)));
    }
}
