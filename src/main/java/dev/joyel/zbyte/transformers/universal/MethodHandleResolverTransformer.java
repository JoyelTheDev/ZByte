package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@TransformerConfig.ConfigOptions(configClass = MethodHandleResolverTransformer.Config.class)
public class MethodHandleResolverTransformer extends ReflectionResolverTransformer {
    private static final String HANDLE = "java/lang/invoke/MethodHandle";
    private static final String HANDLE_DESC = "Ljava/lang/invoke/MethodHandle;";
    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";
    private static final String LOOKUP_FACTORY = "java/lang/invoke/MethodHandles";
    private static final String LOOKUP_DESC = "()Ljava/lang/invoke/MethodHandles$Lookup;";
    private static final String METHOD_TYPE = "java/lang/invoke/MethodType";
    private static final String CLASS = "java/lang/Class";
    private static final String CLASS_DESC = "Ljava/lang/Class;";
    private static final String CLASS_ARRAY_DESC = "[Ljava/lang/Class;";
    private static final String FIND_METHOD_DESC = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;";
    private static final String FIND_CONSTRUCTOR_DESC = "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;";
    private static final String FIND_FIELD_DESC = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;";

    private enum Kind {
        STATIC, VIRTUAL, CONSTRUCTOR, GETTER, STATIC_GETTER, SETTER, STATIC_SETTER
    }

    private static final class Target {
        Kind kind;
        ClassNode holder;
        boolean publicLookup;
        ClassNode requested;
        ClassNode memberOwner;
        int memberAccess;
        Object member;
        String memberName;
        String memberDesc;
        Type[] params;
        Type ret;
        boolean ownerIsInterface;
    }

    private final Map<String, Target> fieldHandles = new HashMap<>();
    private boolean resolved;

    @Override
    public boolean transform() throws Throwable {
        boolean total = false;
        for (int pass = 0; pass < 10; pass++) {
            resolved = false;
            collectFieldHandles();
            resolveAll();
            total |= resolved;
            if (!resolved) {
                break;
            }
        }
        if (removeUnused()) {
            total |= removeUnusedHandleFields();
        }
        return total;
    }

    private boolean removeUnused() {
        return !(getConfig() instanceof Config) || ((Config) getConfig()).isRemoveUnusedHandles();
    }

    private void resolveAll() {
        for (ClassNode classNode : new ArrayList<>(classNodes())) {
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (method.instructions == null || method.instructions.size() == 0) {
                    continue;
                }
                Set<LabelNode> targets = jumpTargets(method);
                currentList = method.instructions;
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getType() != AbstractInsnNode.METHOD_INSN || detached(method.instructions, insn)) {
                        continue;
                    }
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (call.getOpcode() != INVOKEVIRTUAL || !call.owner.equals(HANDLE)
                            || !(call.name.equals("invokeExact") || call.name.equals("invoke"))) {
                        continue;
                    }
                    try {
                        if (resolveInvoke(classNode, method, call, targets)) {
                            resolved = true;
                        }
                    } catch (RuntimeException e) {
                        continue;
                    }
                }
                currentList = null;
            }
        }
    }

    private static boolean isReference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private static String castOperand(Type type) {
        return type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
    }

    private static Type[] expectedParams(Target target) {
        List<Type> out = new ArrayList<>();
        if (target.kind == Kind.VIRTUAL || target.kind == Kind.GETTER || target.kind == Kind.SETTER) {
            out.add(Type.getObjectType(target.requested.name));
        }
        if (target.kind == Kind.STATIC || target.kind == Kind.VIRTUAL || target.kind == Kind.CONSTRUCTOR) {
            for (Type param : target.params) {
                out.add(param);
            }
        } else if (target.kind == Kind.SETTER || target.kind == Kind.STATIC_SETTER) {
            out.add(target.ret);
        }
        return out.toArray(new Type[0]);
    }

    private static Type expectedReturn(Target target) {
        switch (target.kind) {
            case CONSTRUCTOR:
                return Type.getObjectType(target.requested.name);
            case SETTER:
            case STATIC_SETTER:
                return Type.VOID_TYPE;
            default:
                return target.ret;
        }
    }

    private boolean resolveInvoke(ClassNode caller, MethodNode method, MethodInsnNode call, Set<LabelNode> targets) {
        Type[] callParams = Type.getArgumentTypes(call.desc);
        Type callReturn = Type.getReturnType(call.desc);
        List<Slice> operands = operands(call, callParams.length + 1, targets);
        if (operands == null) {
            return false;
        }
        Slice handleSlice = operands.get(0);
        Target target = targetOf(caller, handleSlice, targets);
        if (target == null) {
            return false;
        }
        Type[] expected = expectedParams(target);
        Type expectedReturn = expectedReturn(target);
        if (expected.length != callParams.length) {
            return false;
        }
        boolean exact = call.name.equals("invokeExact");
        boolean[] cast = new boolean[callParams.length];
        for (int i = 0; i < callParams.length; i++) {
            if (callParams[i].equals(expected[i])) {
                continue;
            }
            if (exact || !isReference(callParams[i]) || !isReference(expected[i])) {
                return false;
            }
            cast[i] = true;
        }
        boolean castReturn = false;
        if (!callReturn.equals(expectedReturn)) {
            if (exact || !isReference(callReturn) || !isReference(expectedReturn)) {
                return false;
            }
            castReturn = !callReturn.getDescriptor().equals("Ljava/lang/Object;");
        }
        if (target.kind == Kind.STATIC && target.ownerIsInterface && caller.version < 52) {
            return false;
        }
        if (target.publicLookup || target.holder != caller) {
            if (!lookupAllows(target)) {
                return false;
            }
        }
        if (!accessible(caller, target.requested, target.memberOwner, target.memberAccess, target.member)) {
            return false;
        }

        List<Slice> args = operands.subList(1, operands.size());
        List<AbstractInsnNode> range = range(handleSlice.start, call);
        InsnList replacement = new InsnList();
        String owner = target.requested.name;
        if (target.kind == Kind.CONSTRUCTOR) {
            replacement.add(new TypeInsnNode(NEW, owner));
            replacement.add(new InsnNode(DUP));
        }
        for (int i = 0; i < args.size(); i++) {
            moveInto(replacement, new ArrayList<>(args.get(i).insns));
            if (cast[i]) {
                replacement.add(new TypeInsnNode(CHECKCAST, castOperand(expected[i])));
            }
        }
        boolean isPrivate = (target.memberAccess & ACC_PRIVATE) != 0;
        switch (target.kind) {
            case STATIC:
                replacement.add(new MethodInsnNode(INVOKESTATIC, owner, target.memberName, target.memberDesc, target.ownerIsInterface));
                break;
            case CONSTRUCTOR:
                replacement.add(new MethodInsnNode(INVOKESPECIAL, owner, "<init>", target.memberDesc, false));
                break;
            case VIRTUAL:
                if (isPrivate && caller.name.equals(target.memberOwner.name)) {
                    replacement.add(new MethodInsnNode(INVOKESPECIAL, target.memberOwner.name, target.memberName, target.memberDesc, false));
                } else if (target.ownerIsInterface) {
                    replacement.add(new MethodInsnNode(INVOKEINTERFACE, owner, target.memberName, target.memberDesc, true));
                } else {
                    replacement.add(new MethodInsnNode(INVOKEVIRTUAL, owner, target.memberName, target.memberDesc, false));
                }
                break;
            default:
                String fieldOwner = isPrivate ? target.memberOwner.name : owner;
                int opcode;
                if (target.kind == Kind.GETTER) {
                    opcode = GETFIELD;
                } else if (target.kind == Kind.STATIC_GETTER) {
                    opcode = GETSTATIC;
                } else if (target.kind == Kind.SETTER) {
                    opcode = PUTFIELD;
                } else {
                    opcode = PUTSTATIC;
                }
                replacement.add(new FieldInsnNode(opcode, fieldOwner, target.memberName, target.memberDesc));
                break;
        }
        if (castReturn) {
            replacement.add(new TypeInsnNode(CHECKCAST, castOperand(callReturn)));
        }
        replace(method, call, range, replacement);
        return true;
    }

    private boolean lookupAllows(Target target) {
        if (target.publicLookup) {
            return (target.memberAccess & ACC_PUBLIC) != 0
                    && (target.requested.access & ACC_PUBLIC) != 0
                    && (target.memberOwner.access & ACC_PUBLIC) != 0;
        }
        return isAccessible(target.holder, target.requested, target.memberOwner, target.memberAccess);
    }

    private Target targetOf(ClassNode caller, Slice slice, Set<LabelNode> targets) {
        AbstractInsnNode only = single(slice);
        if (only != null) {
            if (only.getOpcode() == GETSTATIC) {
                FieldInsnNode field = (FieldInsnNode) only;
                if (field.desc.equals(HANDLE_DESC)) {
                    return fieldHandles.get(field.owner + "." + field.name);
                }
            }
            return null;
        }
        return parseHandle(caller, slice, targets);
    }

    private static boolean isLookupFactory(AbstractInsnNode node, String name) {
        if (node == null || node.getOpcode() != INVOKESTATIC) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) node;
        return call.owner.equals(LOOKUP_FACTORY) && call.name.equals(name) && call.desc.equals(LOOKUP_DESC);
    }

    private Type typeConstant(AbstractInsnNode node) {
        if (node != null && node.getOpcode() == GETSTATIC) {
            FieldInsnNode field = (FieldInsnNode) node;
            if (field.owner.equals("java/lang/Void") && field.name.equals("TYPE") && field.desc.equals(CLASS_DESC)) {
                return Type.VOID_TYPE;
            }
        }
        return classConstant(node);
    }

    private Type methodTypeOf(Slice slice, Set<LabelNode> targets) {
        AbstractInsnNode only = single(slice);
        if (only != null) {
            if (only.getOpcode() == LDC) {
                Object cst = ((LdcInsnNode) only).cst;
                if (cst instanceof Type && ((Type) cst).getSort() == Type.METHOD) {
                    return (Type) cst;
                }
            }
            return null;
        }
        List<AbstractInsnNode> insns = slice.insns;
        AbstractInsnNode lastNode = insns.get(insns.size() - 1);
        if (lastNode.getType() != AbstractInsnNode.METHOD_INSN) {
            return null;
        }
        MethodInsnNode create = (MethodInsnNode) lastNode;
        if (create.getOpcode() != INVOKESTATIC || !create.owner.equals(METHOD_TYPE) || !create.name.equals("methodType")) {
            return null;
        }
        Type[] declared = Type.getArgumentTypes(create.desc);
        for (int i = 0; i < declared.length; i++) {
            boolean isClass = declared[i].getDescriptor().equals(CLASS_DESC);
            boolean isArray = declared[i].getDescriptor().equals(CLASS_ARRAY_DESC) && i > 0;
            if (!isClass && !isArray) {
                return null;
            }
        }
        List<Slice> inner = operands(create, declared.length, targets);
        if (inner == null || inner.get(0).start != slice.start) {
            return null;
        }
        Type ret = typeConstant(single(inner.get(0)));
        if (ret == null) {
            return null;
        }
        List<Type> params = new ArrayList<>();
        for (int i = 1; i < declared.length; i++) {
            if (declared[i].getDescriptor().equals(CLASS_ARRAY_DESC)) {
                Type[] array = parseClassArray(inner.get(i));
                if (array == null) {
                    return null;
                }
                for (Type element : array) {
                    params.add(element);
                }
            } else {
                Type param = typeConstant(single(inner.get(i)));
                if (param == null || param.getSort() == Type.VOID) {
                    return null;
                }
                params.add(param);
            }
        }
        return Type.getMethodType(ret, params.toArray(new Type[0]));
    }

    private Target parseHandle(ClassNode holder, Slice slice, Set<LabelNode> targets) {
        List<AbstractInsnNode> insns = slice.insns;
        AbstractInsnNode lastNode = insns.get(insns.size() - 1);
        if (lastNode.getType() != AbstractInsnNode.METHOD_INSN) {
            return null;
        }
        MethodInsnNode find = (MethodInsnNode) lastNode;
        if (find.getOpcode() != INVOKEVIRTUAL || !find.owner.equals(LOOKUP)) {
            return null;
        }
        Kind kind;
        String expectedDesc;
        switch (find.name) {
            case "findStatic":
                kind = Kind.STATIC;
                expectedDesc = FIND_METHOD_DESC;
                break;
            case "findVirtual":
                kind = Kind.VIRTUAL;
                expectedDesc = FIND_METHOD_DESC;
                break;
            case "findConstructor":
                kind = Kind.CONSTRUCTOR;
                expectedDesc = FIND_CONSTRUCTOR_DESC;
                break;
            case "findGetter":
                kind = Kind.GETTER;
                expectedDesc = FIND_FIELD_DESC;
                break;
            case "findStaticGetter":
                kind = Kind.STATIC_GETTER;
                expectedDesc = FIND_FIELD_DESC;
                break;
            case "findSetter":
                kind = Kind.SETTER;
                expectedDesc = FIND_FIELD_DESC;
                break;
            case "findStaticSetter":
                kind = Kind.STATIC_SETTER;
                expectedDesc = FIND_FIELD_DESC;
                break;
            default:
                return null;
        }
        if (!find.desc.equals(expectedDesc)) {
            return null;
        }
        List<Slice> inner = operands(find, Type.getArgumentTypes(find.desc).length + 1, targets);
        if (inner == null || inner.get(0).start != slice.start) {
            return null;
        }
        AbstractInsnNode factory = single(inner.get(0));
        boolean publicLookup = isLookupFactory(factory, "publicLookup");
        if (!publicLookup && !isLookupFactory(factory, "lookup")) {
            return null;
        }
        Type owner = typeConstant(single(inner.get(1)));
        if (owner == null || owner.getSort() != Type.OBJECT) {
            return null;
        }
        ClassNode requested = lookupClass(owner.getInternalName());
        if (requested == null) {
            return null;
        }
        Target target = new Target();
        target.kind = kind;
        target.holder = holder;
        target.publicLookup = publicLookup;
        target.requested = requested;
        target.ownerIsInterface = (requested.access & ACC_INTERFACE) != 0;

        if (kind == Kind.CONSTRUCTOR) {
            Type type = methodTypeOf(inner.get(2), targets);
            if (type == null || type.getReturnType().getSort() != Type.VOID) {
                return null;
            }
            if ((requested.access & (ACC_ABSTRACT | ACC_INTERFACE)) != 0) {
                return null;
            }
            Found found = findMethod(owner.getInternalName(), "<init>", type.getArgumentTypes(), true);
            if (found == null) {
                return null;
            }
            target.params = type.getArgumentTypes();
            target.ret = Type.VOID_TYPE;
            fillMethod(target, found, "<init>");
            return target;
        }
        String name = stringConst(single(inner.get(2)));
        if (name == null || name.startsWith("<")) {
            return null;
        }
        if (kind == Kind.STATIC || kind == Kind.VIRTUAL) {
            Type type = methodTypeOf(inner.get(3), targets);
            if (type == null) {
                return null;
            }
            Found found = findMethod(owner.getInternalName(), name, type.getArgumentTypes(), false);
            if (found == null) {
                found = findMethod(owner.getInternalName(), name, type.getArgumentTypes(), true);
            }
            if (found == null || !Type.getReturnType(found.method.desc).equals(type.getReturnType())) {
                return null;
            }
            boolean isStatic = (found.method.access & ACC_STATIC) != 0;
            if (isStatic != (kind == Kind.STATIC)) {
                return null;
            }
            if (isStatic && found.owner != requested && (found.owner.access & ACC_INTERFACE) != 0) {
                return null;
            }
            target.params = type.getArgumentTypes();
            target.ret = type.getReturnType();
            fillMethod(target, found, name);
            return target;
        }
        Type fieldType = typeConstant(single(inner.get(3)));
        if (fieldType == null || fieldType.getSort() == Type.VOID) {
            return null;
        }
        FieldTarget field = findField(requested, name, false);
        if (field == null) {
            field = findField(requested, name, true);
        }
        if (field == null || !field.field.desc.equals(fieldType.getDescriptor())) {
            return null;
        }
        boolean isStatic = (field.field.access & ACC_STATIC) != 0;
        boolean wantsStatic = kind == Kind.STATIC_GETTER || kind == Kind.STATIC_SETTER;
        if (isStatic != wantsStatic) {
            return null;
        }
        boolean setter = kind == Kind.SETTER || kind == Kind.STATIC_SETTER;
        if (setter && (field.field.access & ACC_FINAL) != 0) {
            return null;
        }
        target.params = new Type[0];
        target.ret = fieldType;
        target.memberOwner = field.memberOwner;
        target.memberAccess = field.field.access;
        target.member = field.field;
        target.memberName = field.field.name;
        target.memberDesc = field.field.desc;
        return target;
    }

    private void fillMethod(Target target, Found found, String name) {
        target.memberOwner = found.owner;
        target.memberAccess = found.method.access;
        target.member = found.method;
        target.memberName = name;
        target.memberDesc = found.method.desc;
    }

    private MethodNode staticInitializer(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.instructions != null) {
                return method;
            }
        }
        return null;
    }

    private FieldNode declaredField(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return field;
            }
        }
        return null;
    }

    private Map<String, Integer> countAccesses(int opcode) {
        Map<String, Integer> counts = new HashMap<>();
        for (ClassNode node : classNodes()) {
            for (MethodNode method : node.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() == opcode) {
                        FieldInsnNode field = (FieldInsnNode) insn;
                        if (field.desc.equals(HANDLE_DESC)) {
                            counts.merge(field.owner + "." + field.name, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return counts;
    }

    private Map<String, Integer> readsByName() {
        Map<String, Integer> counts = new HashMap<>();
        for (ClassNode node : classNodes()) {
            for (MethodNode method : node.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() == GETSTATIC && ((FieldInsnNode) insn).desc.equals(HANDLE_DESC)) {
                        counts.merge(((FieldInsnNode) insn).name, 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    private void collectFieldHandles() {
        fieldHandles.clear();
        Map<String, Integer> writes = countAccesses(PUTSTATIC);
        for (ClassNode node : classNodes()) {
            MethodNode clinit = staticInitializer(node);
            if (clinit == null) {
                continue;
            }
            Set<LabelNode> targets = jumpTargets(clinit);
            for (AbstractInsnNode insn : clinit.instructions.toArray()) {
                if (insn.getOpcode() != PUTSTATIC) {
                    continue;
                }
                FieldInsnNode put = (FieldInsnNode) insn;
                String key = put.owner + "." + put.name;
                if (!put.desc.equals(HANDLE_DESC) || !put.owner.equals(node.name) || writes.getOrDefault(key, 0) != 1) {
                    continue;
                }
                FieldNode field = declaredField(node, put.name, put.desc);
                if (field == null || (field.access & ACC_STATIC) == 0 || (field.access & (ACC_FINAL | ACC_PRIVATE)) == 0) {
                    continue;
                }
                List<Slice> operands = operands(put, 1, targets);
                if (operands == null || single(operands.get(0)) != null) {
                    continue;
                }
                try {
                    Target target = parseHandle(node, operands.get(0), targets);
                    if (target != null) {
                        fieldHandles.put(key, target);
                    }
                } catch (RuntimeException e) {
                    continue;
                }
            }
        }
    }

    private boolean removeUnusedHandleFields() {
        collectFieldHandles();
        Map<String, Integer> reads = readsByName();
        boolean removed = false;
        for (String key : new ArrayList<>(fieldHandles.keySet())) {
            String fieldName = key.substring(key.indexOf('.') + 1);
            if (reads.getOrDefault(fieldName, 0) != 0) {
                continue;
            }
            Target target = fieldHandles.get(key);
            ClassNode node = target.holder;
            String name = key.substring(key.indexOf('.') + 1);
            FieldNode field = declaredField(node, name, HANDLE_DESC);
            MethodNode clinit = staticInitializer(node);
            if (field == null || clinit == null || (field.access & (ACC_PUBLIC | ACC_PROTECTED)) != 0) {
                continue;
            }
            Set<LabelNode> targets = jumpTargets(clinit);
            for (AbstractInsnNode insn : clinit.instructions.toArray()) {
                if (insn.getOpcode() != PUTSTATIC) {
                    continue;
                }
                FieldInsnNode put = (FieldInsnNode) insn;
                if (!put.owner.equals(node.name) || !put.name.equals(name) || !put.desc.equals(HANDLE_DESC)) {
                    continue;
                }
                List<Slice> operands = operands(put, 1, targets);
                if (operands == null) {
                    break;
                }
                for (AbstractInsnNode part : new ArrayList<>(operands.get(0).insns)) {
                    clinit.instructions.remove(part);
                }
                clinit.instructions.remove(put);
                node.fields.remove(field);
                removed = true;
                break;
            }
        }
        return removed;
    }

    public static class Config extends ReflectionResolverTransformer.Config {
        private boolean removeUnusedHandles = true;

        public Config() {
            super(MethodHandleResolverTransformer.class);
        }

        public boolean isRemoveUnusedHandles() {
            return removeUnusedHandles;
        }

        public void setRemoveUnusedHandles(boolean removeUnusedHandles) {
            this.removeUnusedHandles = removeUnusedHandles;
        }
    }
}
