package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public abstract class AbstractPoolTransformer extends Transformer<TransformerConfig> {

    private static final int MAX_POOL_SIZE = 65536;

    protected static final class Parsed {
        final Object value;
        final AbstractInsnNode last;

        Parsed(Object value, AbstractInsnNode last) {
            this.value = value;
            this.last = last;
        }
    }

    protected static final class Load {
        final List<AbstractInsnNode> insns;
        final char type;

        Load(List<AbstractInsnNode> insns, char type) {
            this.insns = insns;
            this.type = type;
        }
    }

    private static final class Pool {
        final FieldInsnNode put;
        final MethodNode initMethod;
        final Object[] values;
        final Set<AbstractInsnNode> storeInsns;

        Pool(FieldInsnNode put, MethodNode initMethod, Object[] values, Set<AbstractInsnNode> storeInsns) {
            this.put = put;
            this.initMethod = initMethod;
            this.values = values;
            this.storeInsns = storeInsns;
        }
    }

    protected abstract boolean creationMatches(AbstractInsnNode creation, String fieldDesc);

    protected abstract Parsed readStoredValue(AbstractInsnNode start, String fieldDesc, Set<LabelNode> referenced);

    protected abstract boolean isStore(AbstractInsnNode insn, String fieldDesc);

    protected abstract Load readLoad(AbstractInsnNode start, String fieldDesc, Set<LabelNode> referenced);

    protected abstract Object defaultValue(String fieldDesc);

    protected abstract Object normalize(Object value, String fieldDesc);

    private int replaced;

    @Override
    public boolean transform() throws Throwable {
        replaced = 0;
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : new ArrayList<>(classNode.methods)) {
                if (!classNode.methods.contains(methodNode)) {
                    continue;
                }
                Set<LabelNode> referenced = UniversalSupport.referencedLabels(methodNode);
                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    if (insn.getOpcode() != ANEWARRAY && insn.getOpcode() != NEWARRAY) {
                        continue;
                    }
                    Pool pool = readPool(classNode, methodNode, insn, referenced);
                    if (pool == null) {
                        continue;
                    }
                    changed |= apply(classNode, pool);
                    break;
                }
            }
        }
        if (replaced > 0) {
            logger.info("Inlined {} pool values", replaced);
        }
        return changed;
    }

    private Pool readPool(ClassNode classNode, MethodNode method, AbstractInsnNode creation, Set<LabelNode> referenced) {
        Integer size = UniversalSupport.intConstant(UniversalSupport.previous(creation, referenced));
        if (size == null || size < 0 || size > MAX_POOL_SIZE) {
            return null;
        }
        Object[] raw = new Object[size];
        Set<AbstractInsnNode> storeInsns = Collections.newSetFromMap(new IdentityHashMap<>());
        AbstractInsnNode cursor = UniversalSupport.next(creation, referenced);
        while (cursor != null && cursor.getOpcode() == DUP) {
            AbstractInsnNode indexInsn = UniversalSupport.next(cursor, referenced);
            Integer index = UniversalSupport.intConstant(indexInsn);
            if (index == null) {
                return null;
            }
            AbstractInsnNode valueStart = UniversalSupport.next(indexInsn, referenced);
            Parsed parsed = valueStart == null ? null : readStoredValue(valueStart, "", referenced);
            if (parsed == null) {
                return null;
            }
            AbstractInsnNode store = UniversalSupport.next(parsed.last, referenced);
            if (store == null || !isStore(store, "")) {
                return null;
            }
            if (index < 0 || index >= size || raw[index] != null) {
                return null;
            }
            raw[index] = parsed.value;
            cursor = UniversalSupport.next(store, referenced);
        }
        if (cursor == null || cursor.getOpcode() != PUTSTATIC) {
            return null;
        }
        FieldInsnNode put = (FieldInsnNode) cursor;
        if (!put.owner.equals(classNode.name) || !creationMatches(creation, put.desc)) {
            return null;
        }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!UniversalSupport.isFieldRef(insn, GETSTATIC, put.owner, put.name, put.desc)) {
                continue;
            }
            AbstractInsnNode indexInsn = UniversalSupport.next(insn, referenced);
            Integer index = UniversalSupport.intConstant(indexInsn);
            if (index == null) {
                continue;
            }
            AbstractInsnNode valueStart = UniversalSupport.next(indexInsn, referenced);
            Parsed parsed = valueStart == null ? null : readStoredValue(valueStart, put.desc, referenced);
            if (parsed == null) {
                continue;
            }
            AbstractInsnNode store = UniversalSupport.next(parsed.last, referenced);
            if (store == null || !isStore(store, put.desc)) {
                continue;
            }
            if (index < 0 || index >= size || raw[index] != null) {
                return null;
            }
            raw[index] = parsed.value;
            storeInsns.add(insn);
        }
        Object[] values = new Object[size];
        for (int i = 0; i < size; i++) {
            Object value = raw[i] != null ? raw[i] : defaultValue(put.desc);
            if (value == null) {
                return null;
            }
            values[i] = normalize(value, put.desc);
            if (values[i] == null) {
                return null;
            }
        }
        return new Pool(put, method, values, storeInsns);
    }

    private boolean apply(ClassNode classNode, Pool pool) {
        FieldInsnNode put = pool.put;
        boolean changed = false;
        boolean unmatched = false;
        for (MethodNode method : classNode.methods) {
            Set<LabelNode> referenced = UniversalSupport.referencedLabels(method);
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!UniversalSupport.isFieldRef(insn, GETSTATIC, put.owner, put.name, put.desc)) {
                    continue;
                }
                if (pool.storeInsns.contains(insn)) {
                    continue;
                }
                AbstractInsnNode indexInsn = UniversalSupport.next(insn, referenced);
                Integer index = UniversalSupport.intConstant(indexInsn);
                Load load = index == null ? null : readLoad(UniversalSupport.next(indexInsn, referenced), put.desc, referenced);
                if (load == null || index < 0 || index >= pool.values.length) {
                    unmatched = true;
                    continue;
                }
                Object value = convert(pool.values[index], load.type);
                if (value == null) {
                    unmatched = true;
                    continue;
                }
                AbstractInsnNode last = load.insns.get(load.insns.size() - 1);
                method.instructions.set(last, UniversalSupport.constantInsn(value));
                for (int i = 0; i < load.insns.size() - 1; i++) {
                    method.instructions.remove(load.insns.get(i));
                }
                method.instructions.remove(indexInsn);
                method.instructions.remove(insn);
                changed = true;
                replaced++;
            }
        }
        if (changed && !unmatched) {
            removeInitialization(classNode, pool);
        }
        return changed;
    }

    private void removeInitialization(ClassNode classNode, Pool pool) {
        MethodNode init = pool.initMethod;
        if ((classNode.fields.stream().noneMatch(f -> f.name.equals(pool.put.name) && f.desc.equals(pool.put.desc) && (f.access & ACC_PRIVATE) != 0))) {
            return;
        }
        if (init.name.equals("<clinit>") || (init.access & ACC_STATIC) == 0 || !init.desc.equals("()V") || !isDedicatedInit(init, pool.put)) {
            return;
        }
        List<MethodInsnNode> calls = new ArrayList<>();
        for (ClassNode other : classNodes()) {
            for (MethodNode method : other.methods) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) insn;
                        if (call.owner.equals(classNode.name) && call.name.equals(init.name) && call.desc.equals(init.desc)) {
                            if (other != classNode || !method.name.equals("<clinit>")) {
                                return;
                            }
                            calls.add(call);
                        }
                    }
                }
            }
        }
        MethodNode clinit = null;
        for (MethodNode method : classNode.methods) {
            if (method.name.equals("<clinit>")) {
                clinit = method;
            }
        }
        if (clinit != null) {
            for (MethodInsnNode call : calls) {
                clinit.instructions.remove(call);
            }
        }
        classNode.methods.remove(init);
        classNode.fields.removeIf(f -> f.name.equals(pool.put.name) && f.desc.equals(pool.put.desc));
    }

    private boolean isDedicatedInit(MethodNode init, FieldInsnNode put) {
        for (AbstractInsnNode insn : init.instructions.toArray()) {
            int opcode = insn.getOpcode();
            if (opcode == -1) {
                continue;
            }
            if (opcode == GETSTATIC || opcode == PUTSTATIC) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (!field.owner.equals(put.owner) || !field.name.equals(put.name) || !field.desc.equals(put.desc)) {
                    return false;
                }
                continue;
            }
            if (opcode == INVOKESTATIC) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!call.name.equals("valueOf") || !call.owner.startsWith("java/lang/")) {
                    return false;
                }
                continue;
            }
            boolean allowed = (opcode >= ICONST_M1 && opcode <= LDC)
                    || opcode == DUP
                    || opcode == ANEWARRAY
                    || opcode == NEWARRAY
                    || (opcode >= IASTORE && opcode <= SASTORE)
                    || opcode == RETURN;
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static Object convert(Object value, char type) {
        if (type == 'A') {
            return value instanceof String ? value : null;
        }
        if (!(value instanceof Number)) {
            return null;
        }
        Number number = (Number) value;
        switch (type) {
            case 'I':
                return number.intValue();
            case 'J':
                return number.longValue();
            case 'F':
                return number.floatValue();
            case 'D':
                return number.doubleValue();
            default:
                return null;
        }
    }
}
