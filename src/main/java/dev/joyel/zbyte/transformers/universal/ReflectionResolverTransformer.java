package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import java.util.*;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@TransformerConfig.ConfigOptions(configClass = ReflectionResolverTransformer.Config.class)
public class ReflectionResolverTransformer extends Transformer<ReflectionResolverTransformer.Config> {
    private boolean changed;

    private void markChange() {
        changed = true;
    }

    @Override
    public boolean transform() throws Throwable {
        boolean total = false;
        for (int pass = 0; pass < 10; pass++) {
            changed = false;
            run();
            total |= changed;
            if (!changed) {
                break;
            }
        }
        return total;
    }

    protected boolean widenAccess() {
        return getConfig() != null && getConfig().isWidenAccess();
    }

  private static final String CLASS = "java/lang/Class";
  private static final String METHOD = "java/lang/reflect/Method";
  private static final String CONSTRUCTOR = "java/lang/reflect/Constructor";
  private static final String FIELD = "java/lang/reflect/Field";
  private static final String OBJECT = "java/lang/Object";
  private static final String LOOKUP_METHOD_DESC = "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;";
  private static final String LOOKUP_CONSTRUCTOR_DESC = "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;";
  private static final String LOOKUP_FIELD_DESC = "(Ljava/lang/String;)Ljava/lang/reflect/Field;";

  private final Map<String, ClassNode> jdkClasses = new HashMap<>();
  private InsnList currentList;
  private final Set<AbstractInsnNode> moved = new HashSet<>();

    private void run() {
    for (ClassNode classNode : new ArrayList<>(classNodes())) {
      for (MethodNode method : new ArrayList<>(classNode.methods)) {
        if (method.instructions == null || method.instructions.size() == 0) {
          continue;
        }
        processMethod(classNode, method);
      }
    }
  }

  private void processMethod(ClassNode caller, MethodNode method) {
    Set<LabelNode> targets = jumpTargets(method);
    currentList = method.instructions;
    for (AbstractInsnNode insn : method.instructions.toArray()) {
      if (insn.getType() != AbstractInsnNode.METHOD_INSN || detached(method.instructions, insn)) {
        continue;
      }
      MethodInsnNode call = (MethodInsnNode) insn;
      boolean done = false;
      try {
        if (isCall(call, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", INVOKESTATIC)) {
          done = resolveForName(caller, method, call, targets);
        } else if (isCall(call, METHOD, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", INVOKEVIRTUAL)) {
          done = resolveMethodInvoke(caller, method, call, targets);
        } else if (isCall(call, CONSTRUCTOR, "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;", INVOKEVIRTUAL)) {
          done = resolveNewInstance(caller, method, call, targets);
        } else if (isCall(call, FIELD, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", INVOKEVIRTUAL)) {
          done = resolveFieldGet(caller, method, call, targets);
        } else if (isCall(call, FIELD, "set", "(Ljava/lang/Object;Ljava/lang/Object;)V", INVOKEVIRTUAL)) {
          done = resolveFieldSet(caller, method, call, targets);
        }
      } catch (RuntimeException e) {
        done = false;
      }
      if (done) {
        markChange();
      }
    }
    currentList = null;
  }

  private boolean resolveForName(ClassNode caller, MethodNode method, MethodInsnNode call, Set<LabelNode> targets) {
    List<Slice> operands = operands(call, 1, targets);
    if (operands == null) {
      return false;
    }
    List<AbstractInsnNode> range = range(operands.get(0).start, call);
    String name = stringConst(single(operands.get(0)));
    if (name == null || name.isEmpty() || caller.version < 49) {
      return false;
    }
    String internal = name.replace('.', '/');
    Type type;
    if (internal.startsWith("[")) {
      try {
        type = Type.getType(internal);
      } catch (RuntimeException e) {
        return false;
      }
      Type element = type.getElementType();
      if (element.getSort() == Type.OBJECT && !classSafeToLoad(element.getInternalName())) {
        return false;
      }
    } else {
      if (!classSafeToLoad(internal)) {
        return false;
      }
      type = Type.getObjectType(internal);
    }
    InsnList replacement = new InsnList();
    replacement.add(new LdcInsnNode(type));
    replace(method, call, range, replacement);
    return true;
  }

  private boolean resolveMethodInvoke(ClassNode caller, MethodNode method, MethodInsnNode call, Set<LabelNode> targets) {
    List<Slice> operands = operands(call, 3, targets);
    if (operands == null) {
      return false;
    }
    List<AbstractInsnNode> range = range(operands.get(0).start, call);
    Slice methodSlice = operands.get(0);
    Slice objSlice = operands.get(1);
    Slice argsSlice = operands.get(2);
    Lookup lookup = parseLookup(methodSlice, METHOD, LOOKUP_METHOD_DESC, new String[] {"getMethod", "getDeclaredMethod"}, targets);
    if (lookup == null) {
      return false;
    }
    Type owner = classConstant(single(lookup.operands.get(0)));
    String name = stringConst(single(lookup.operands.get(1)));
    Type[] params = parseClassArray(lookup.operands.get(2));
    if (owner == null || owner.getSort() != Type.OBJECT || name == null || params == null || name.startsWith("<")) {
      return false;
    }
    List<List<AbstractInsnNode>> values = parseArgs(argsSlice);
    if (values == null || values.size() != params.length) {
      return false;
    }
    Found found = findMethod(owner.getInternalName(), name, params, lookup.declared);
    if (found == null) {
      return false;
    }
    ClassNode requested = lookupClass(owner.getInternalName());
    if (requested == null || !accessible(caller, requested, found.owner, found.method.access, found.method)) {
      return false;
    }
    boolean isStatic = (found.method.access & ACC_STATIC) != 0;
    boolean ownerIsInterface = (requested.access & ACC_INTERFACE) != 0;
    if (isStatic && ownerIsInterface && caller.version < 52) {
      return false;
    }
    List<List<AbstractInsnNode>> converted = convertAll(values, params);
    if (converted == null) {
      return false;
    }
    InsnList replacement = new InsnList();
    if (!appendReceiver(replacement, objSlice, isStatic, owner.getInternalName())) {
      return false;
    }
    for (List<AbstractInsnNode> arg : converted) {
      moveInto(replacement, arg);
    }
    int opcode;
    String callOwner = owner.getInternalName();
    if (isStatic) {
      opcode = INVOKESTATIC;
    } else if ((found.method.access & ACC_PRIVATE) != 0 && caller.name.equals(found.owner.name)) {
      opcode = INVOKESPECIAL;
      callOwner = found.owner.name;
    } else if (ownerIsInterface) {
      opcode = INVOKEINTERFACE;
    } else {
      opcode = INVOKEVIRTUAL;
    }
    replacement.add(new MethodInsnNode(opcode, callOwner, found.method.name, found.method.desc, ownerIsInterface));
    appendResultBoxing(replacement, Type.getReturnType(found.method.desc));
    replace(method, call, range, replacement);
    return true;
  }

  private boolean resolveNewInstance(ClassNode caller, MethodNode method, MethodInsnNode call, Set<LabelNode> targets) {
    List<Slice> operands = operands(call, 2, targets);
    if (operands == null) {
      return false;
    }
    List<AbstractInsnNode> range = range(operands.get(0).start, call);
    Slice ctorSlice = operands.get(0);
    Slice argsSlice = operands.get(1);
    Lookup lookup = parseLookup(ctorSlice, CONSTRUCTOR, LOOKUP_CONSTRUCTOR_DESC, new String[] {"getConstructor", "getDeclaredConstructor"}, targets);
    if (lookup == null) {
      return false;
    }
    Type owner = classConstant(single(lookup.operands.get(0)));
    Type[] params = parseClassArray(lookup.operands.get(1));
    if (owner == null || owner.getSort() != Type.OBJECT || params == null) {
      return false;
    }
    List<List<AbstractInsnNode>> values = parseArgs(argsSlice);
    if (values == null || values.size() != params.length) {
      return false;
    }
    ClassNode requested = lookupClass(owner.getInternalName());
    if (requested == null || (requested.access & (ACC_ABSTRACT | ACC_INTERFACE)) != 0) {
      return false;
    }
    MethodNode ctor = null;
    for (MethodNode candidate : requested.methods) {
      if (candidate.name.equals("<init>") && Arrays.equals(Type.getArgumentTypes(candidate.desc), params)) {
        ctor = candidate;
      }
    }
    if (ctor == null || (!lookup.declared && (ctor.access & ACC_PUBLIC) == 0)) {
      return false;
    }
    if (!accessible(caller, requested, requested, ctor.access, ctor)) {
      return false;
    }
    List<List<AbstractInsnNode>> converted = convertAll(values, params);
    if (converted == null) {
      return false;
    }
    InsnList replacement = new InsnList();
    replacement.add(new TypeInsnNode(NEW, owner.getInternalName()));
    replacement.add(new InsnNode(DUP));
    for (List<AbstractInsnNode> arg : converted) {
      moveInto(replacement, arg);
    }
    replacement.add(new MethodInsnNode(INVOKESPECIAL, owner.getInternalName(), "<init>", ctor.desc, false));
    replace(method, call, range, replacement);
    return true;
  }

  private boolean resolveFieldGet(ClassNode caller, MethodNode method, MethodInsnNode call, Set<LabelNode> targets) {
    List<Slice> operands = operands(call, 2, targets);
    if (operands == null) {
      return false;
    }
    List<AbstractInsnNode> range = range(operands.get(0).start, call);
    Slice fieldSlice = operands.get(0);
    Slice objSlice = operands.get(1);
    FieldTarget target = resolveFieldTarget(caller, fieldSlice, targets);
    if (target == null) {
      return false;
    }
    boolean isStatic = (target.field.access & ACC_STATIC) != 0;
    InsnList replacement = new InsnList();
    if (!appendReceiver(replacement, objSlice, isStatic, target.requested.name)) {
      return false;
    }
    String fieldOwner = (target.field.access & ACC_PRIVATE) != 0 ? target.memberOwner.name : target.requested.name;
    replacement.add(new FieldInsnNode(isStatic ? GETSTATIC : GETFIELD, fieldOwner, target.field.name, target.field.desc));
    appendResultBoxing(replacement, Type.getType(target.field.desc));
    replace(method, call, range, replacement);
    return true;
  }

  private boolean resolveFieldSet(ClassNode caller, MethodNode method, MethodInsnNode call, Set<LabelNode> targets) {
    List<Slice> operands = operands(call, 3, targets);
    if (operands == null) {
      return false;
    }
    List<AbstractInsnNode> range = range(operands.get(0).start, call);
    Slice fieldSlice = operands.get(0);
    Slice objSlice = operands.get(1);
    Slice valueSlice = operands.get(2);
    FieldTarget target = resolveFieldTarget(caller, fieldSlice, targets);
    if (target == null || (target.field.access & ACC_FINAL) != 0) {
      return false;
    }
    List<AbstractInsnNode> converted = convertArg(new ArrayList<>(valueSlice.insns), Type.getType(target.field.desc));
    if (converted == null) {
      return false;
    }
    boolean isStatic = (target.field.access & ACC_STATIC) != 0;
    InsnList replacement = new InsnList();
    if (!appendReceiver(replacement, objSlice, isStatic, target.requested.name)) {
      return false;
    }
    moveInto(replacement, converted);
    String fieldOwner = (target.field.access & ACC_PRIVATE) != 0 ? target.memberOwner.name : target.requested.name;
    replacement.add(new FieldInsnNode(isStatic ? PUTSTATIC : PUTFIELD, fieldOwner, target.field.name, target.field.desc));
    replace(method, call, range, replacement);
    return true;
  }

  private FieldTarget resolveFieldTarget(ClassNode caller, Slice fieldSlice, Set<LabelNode> targets) {
    Lookup lookup = parseLookup(fieldSlice, FIELD, LOOKUP_FIELD_DESC, new String[] {"getField", "getDeclaredField"}, targets);
    if (lookup == null) {
      return null;
    }
    Type owner = classConstant(single(lookup.operands.get(0)));
    String name = stringConst(single(lookup.operands.get(1)));
    if (owner == null || owner.getSort() != Type.OBJECT || name == null) {
      return null;
    }
    ClassNode requested = lookupClass(owner.getInternalName());
    if (requested == null) {
      return null;
    }
    FieldTarget target = findField(requested, name, lookup.declared);
    if (target == null) {
      return null;
    }
    if (!accessible(caller, requested, target.memberOwner, target.field.access, target.field)) {
      return null;
    }
    return target;
  }

  private boolean appendReceiver(InsnList out, Slice objSlice, boolean isStatic, String ownerInternal) {
    List<AbstractInsnNode> insns = objSlice.insns;
    if (isStatic) {
      boolean nullConst = insns.size() == 1 && insns.get(0).getOpcode() == ACONST_NULL;
      if (!nullConst) {
        moveInto(out, new ArrayList<>(insns));
        out.add(new InsnNode(POP));
      }
      return true;
    }
    if (insns.size() == 1 && insns.get(0).getOpcode() == ACONST_NULL) {
      return false;
    }
    moveInto(out, new ArrayList<>(insns));
    out.add(new TypeInsnNode(CHECKCAST, ownerInternal));
    return true;
  }

  private void appendResultBoxing(InsnList out, Type returnType) {
    switch (returnType.getSort()) {
      case Type.VOID:
        out.add(new InsnNode(ACONST_NULL));
        break;
      case Type.BOOLEAN:
      case Type.BYTE:
      case Type.CHAR:
      case Type.SHORT:
      case Type.INT:
      case Type.LONG:
      case Type.FLOAT:
      case Type.DOUBLE:
        out.add(new MethodInsnNode(INVOKESTATIC, wrapperOf(returnType), "valueOf", "(" + returnType.getDescriptor() + ")L" + wrapperOf(returnType) + ";", false));
        break;
      default:
        break;
    }
  }

  private List<List<AbstractInsnNode>> convertAll(List<List<AbstractInsnNode>> values, Type[] params) {
    List<List<AbstractInsnNode>> result = new ArrayList<>();
    for (int i = 0; i < params.length; i++) {
      List<AbstractInsnNode> converted = convertArg(new ArrayList<>(values.get(i)), params[i]);
      if (converted == null) {
        return null;
      }
      result.add(converted);
    }
    return result;
  }

  private List<AbstractInsnNode> convertArg(List<AbstractInsnNode> value, Type param) {
    if (value.isEmpty()) {
      return null;
    }
    int sort = param.getSort();
    if (sort >= Type.BOOLEAN && sort <= Type.DOUBLE) {
      AbstractInsnNode last = value.get(value.size() - 1);
      if (last.getType() != AbstractInsnNode.METHOD_INSN) {
        return null;
      }
      MethodInsnNode box = (MethodInsnNode) last;
      String wrapper = wrapperOf(param);
      if (box.getOpcode() != INVOKESTATIC || !box.owner.equals(wrapper) || !box.name.equals("valueOf")
          || !box.desc.equals("(" + param.getDescriptor() + ")L" + wrapper + ";")) {
        return null;
      }
      value.remove(value.size() - 1);
      return value;
    }
    if (sort == Type.OBJECT) {
      if (!param.getInternalName().equals(OBJECT)) {
        value.add(new TypeInsnNode(CHECKCAST, param.getInternalName()));
      }
      return value;
    }
    if (sort == Type.ARRAY) {
      value.add(new TypeInsnNode(CHECKCAST, param.getDescriptor()));
      return value;
    }
    return null;
  }

  private static String wrapperOf(Type primitive) {
    switch (primitive.getSort()) {
      case Type.BOOLEAN:
        return "java/lang/Boolean";
      case Type.BYTE:
        return "java/lang/Byte";
      case Type.CHAR:
        return "java/lang/Character";
      case Type.SHORT:
        return "java/lang/Short";
      case Type.INT:
        return "java/lang/Integer";
      case Type.LONG:
        return "java/lang/Long";
      case Type.FLOAT:
        return "java/lang/Float";
      case Type.DOUBLE:
        return "java/lang/Double";
      default:
        throw new IllegalArgumentException();
    }
  }

  private Lookup parseLookup(Slice slice, String resultType, String lookupDesc, String[] lookupNames, Set<LabelNode> targets) {
    List<AbstractInsnNode> insns = slice.insns;
    int size = insns.size();
    if (size == 0) {
      return null;
    }
    int getIndex = size - 1;
    AbstractInsnNode last = insns.get(getIndex);
    if (last.getType() == AbstractInsnNode.METHOD_INSN) {
      MethodInsnNode m = (MethodInsnNode) last;
      if (m.getOpcode() == INVOKEVIRTUAL && m.owner.equals(resultType) && m.name.equals("setAccessible") && m.desc.equals("(Z)V")) {
        if (size < 4 || insns.get(size - 2).getOpcode() != ICONST_1 || insns.get(size - 3).getOpcode() != DUP) {
          return null;
        }
        getIndex = size - 4;
      }
    }
    AbstractInsnNode getNode = insns.get(getIndex);
    if (getNode.getType() != AbstractInsnNode.METHOD_INSN) {
      return null;
    }
    MethodInsnNode get = (MethodInsnNode) getNode;
    if (get.getOpcode() != INVOKEVIRTUAL || !get.owner.equals(CLASS) || !get.desc.equals(lookupDesc)) {
      return null;
    }
    boolean matched = false;
    boolean declared = false;
    for (String candidate : lookupNames) {
      if (get.name.equals(candidate)) {
        matched = true;
        declared = candidate.startsWith("getDeclared");
      }
    }
    if (!matched) {
      return null;
    }
    int count = Type.getArgumentTypes(get.desc).length + 1;
    List<Slice> inner = operands(get, count, targets);
    if (inner == null || inner.get(0).start != slice.start) {
      return null;
    }
    Lookup lookup = new Lookup();
    lookup.operands = inner;
    lookup.declared = declared;
    return lookup;
  }

  private Type[] parseClassArray(Slice slice) {
    List<AbstractInsnNode> insns = slice.insns;
    if (insns.size() == 1 && insns.get(0).getOpcode() == ACONST_NULL) {
      return new Type[0];
    }
    if (insns.size() < 2 || !isInt(insns.get(0))) {
      return null;
    }
    int n = intValue(insns.get(0));
    AbstractInsnNode create = insns.get(1);
    if (n < 0 || create.getOpcode() != ANEWARRAY || !((TypeInsnNode) create).desc.equals(CLASS) || insns.size() != 2 + 4 * n) {
      return null;
    }
    Type[] types = new Type[n];
    for (int i = 0; i < n; i++) {
      int base = 2 + 4 * i;
      if (insns.get(base).getOpcode() != DUP || !isInt(insns.get(base + 1)) || intValue(insns.get(base + 1)) != i || insns.get(base + 3).getOpcode() != AASTORE) {
        return null;
      }
      Type type = classConstant(insns.get(base + 2));
      if (type == null) {
        return null;
      }
      types[i] = type;
    }
    return types;
  }

  private List<List<AbstractInsnNode>> parseArgs(Slice slice) {
    List<AbstractInsnNode> insns = slice.insns;
    List<List<AbstractInsnNode>> values = new ArrayList<>();
    if (insns.size() == 1 && insns.get(0).getOpcode() == ACONST_NULL) {
      return values;
    }
    if (insns.size() < 2 || !isInt(insns.get(0))) {
      return null;
    }
    int n = intValue(insns.get(0));
    AbstractInsnNode create = insns.get(1);
    if (n < 0 || create.getOpcode() != ANEWARRAY || !((TypeInsnNode) create).desc.equals(OBJECT)) {
      return null;
    }
    int pos = 2;
    for (int i = 0; i < n; i++) {
      if (pos + 2 >= insns.size() || insns.get(pos).getOpcode() != DUP || !isInt(insns.get(pos + 1)) || intValue(insns.get(pos + 1)) != i) {
        return null;
      }
      pos += 2;
      int extra = 2;
      List<AbstractInsnNode> value = new ArrayList<>();
      boolean closed = false;
      while (pos < insns.size()) {
        AbstractInsnNode node = insns.get(pos);
        if (node.getOpcode() == AASTORE && extra == 3) {
          closed = true;
          pos++;
          break;
        }
        int[] effect = effect(node);
        if (effect == null || extra - effect[0] < 2) {
          return null;
        }
        extra = extra - effect[0] + effect[1];
        value.add(node);
        pos++;
      }
      if (!closed || value.isEmpty()) {
        return null;
      }
      values.add(value);
    }
    if (pos != insns.size()) {
      return null;
    }
    return values;
  }

  private Type classConstant(AbstractInsnNode node) {
    if (node == null) {
      return null;
    }
    if (node.getOpcode() == LDC) {
      Object cst = ((LdcInsnNode) node).cst;
      if (cst instanceof Type) {
        Type type = (Type) cst;
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
          return type;
        }
      }
      return null;
    }
    if (node.getOpcode() == GETSTATIC) {
      FieldInsnNode field = (FieldInsnNode) node;
      if (field.name.equals("TYPE") && field.desc.equals("Ljava/lang/Class;")) {
        switch (field.owner) {
          case "java/lang/Integer":
            return Type.INT_TYPE;
          case "java/lang/Long":
            return Type.LONG_TYPE;
          case "java/lang/Boolean":
            return Type.BOOLEAN_TYPE;
          case "java/lang/Byte":
            return Type.BYTE_TYPE;
          case "java/lang/Character":
            return Type.CHAR_TYPE;
          case "java/lang/Short":
            return Type.SHORT_TYPE;
          case "java/lang/Float":
            return Type.FLOAT_TYPE;
          case "java/lang/Double":
            return Type.DOUBLE_TYPE;
          default:
            return null;
        }
      }
    }
    return null;
  }

  private static String stringConst(AbstractInsnNode node) {
    if (node != null && node.getOpcode() == LDC && ((LdcInsnNode) node).cst instanceof String) {
      return (String) ((LdcInsnNode) node).cst;
    }
    return null;
  }

  private static AbstractInsnNode single(Slice slice) {
    return slice.insns.size() == 1 ? slice.insns.get(0) : null;
  }

  private static boolean isInt(AbstractInsnNode node) {
    int op = node.getOpcode();
    if (op >= ICONST_M1 && op <= ICONST_5 || op == BIPUSH || op == SIPUSH) {
      return true;
    }
    return op == LDC && ((LdcInsnNode) node).cst instanceof Integer;
  }

  private static int intValue(AbstractInsnNode node) {
    int op = node.getOpcode();
    if (op >= ICONST_M1 && op <= ICONST_5) {
      return op - ICONST_0;
    }
    if (op == BIPUSH || op == SIPUSH) {
      return ((IntInsnNode) node).operand;
    }
    return (Integer) ((LdcInsnNode) node).cst;
  }

  private ClassNode lookupClass(String name) {
    ClassNode node = classes.get(name);
    if (node != null) {
      return node;
    }
    node = classpath.get(name);
    if (node != null) {
      return node;
    }
    if (jdkClasses.containsKey(name)) {
      return jdkClasses.get(name);
    }
    ClassNode loaded = null;
    try (java.io.InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(name + ".class")) {
      if (in != null) {
        loaded = new ClassNode();
        new org.objectweb.asm.ClassReader(in).accept(loaded, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG);
      }
    } catch (java.io.IOException | RuntimeException e) {
      loaded = null;
    }
    jdkClasses.put(name, loaded);
    return loaded;
  }

  private boolean classSafeToLoad(String internal) {
    ClassNode node = lookupClass(internal);
    if (node == null) {
      return false;
    }
    if (internal.startsWith("java/")) {
      return true;
    }
    for (MethodNode m : node.methods) {
      if (m.name.equals("<clinit>")) {
        return false;
      }
    }
    return true;
  }

  private Found findMethod(String ownerName, String name, Type[] params, boolean declaredOnly) {
    List<String> queue = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    queue.add(ownerName);
    for (int i = 0; i < queue.size(); i++) {
      String current = queue.get(i);
      if (!seen.add(current)) {
        continue;
      }
      ClassNode node = lookupClass(current);
      if (node == null) {
        return null;
      }
      List<MethodNode> candidates = new ArrayList<>();
      List<MethodNode> bridges = new ArrayList<>();
      for (MethodNode m : node.methods) {
        if (m.name.equals(name) && Arrays.equals(Type.getArgumentTypes(m.desc), params)) {
          if ((m.access & ACC_BRIDGE) != 0) {
            bridges.add(m);
          } else {
            candidates.add(m);
          }
        }
      }
      if (candidates.isEmpty() && !bridges.isEmpty()) {
        candidates = bridges;
      }
      if (candidates.size() == 1) {
        MethodNode m = candidates.get(0);
        if (!declaredOnly && (m.access & ACC_PUBLIC) == 0) {
          return null;
        }
        Found found = new Found();
        found.owner = node;
        found.method = m;
        return found;
      }
      if (candidates.size() > 1) {
        return null;
      }
      if (declaredOnly) {
        return null;
      }
      if (node.superName != null) {
        queue.add(node.superName);
      }
      queue.addAll(node.interfaces);
    }
    return null;
  }

  private FieldTarget findField(ClassNode requested, String name, boolean declaredOnly) {
    List<String> queue = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    queue.add(requested.name);
    for (int i = 0; i < queue.size(); i++) {
      String current = queue.get(i);
      if (!seen.add(current)) {
        continue;
      }
      ClassNode node = lookupClass(current);
      if (node == null) {
        return null;
      }
      FieldNode match = null;
      for (FieldNode f : node.fields) {
        if (f.name.equals(name)) {
          if (match != null) {
            return null;
          }
          match = f;
        }
      }
      if (match != null) {
        if (!declaredOnly && (match.access & ACC_PUBLIC) == 0) {
          return null;
        }
        FieldTarget target = new FieldTarget();
        target.requested = requested;
        target.memberOwner = node;
        target.field = match;
        return target;
      }
      if (declaredOnly) {
        return null;
      }
      queue.addAll(node.interfaces);
      if (node.superName != null) {
        queue.add(node.superName);
      }
    }
    return null;
  }

  private boolean accessible(ClassNode caller, ClassNode requested, ClassNode memberOwner, int access, Object member) {
    if (isAccessible(caller, requested, memberOwner, access)) {
      return true;
    }
    if (!widenAccess()) {
      return false;
    }
    if (!classes.containsKey(requested.name) || !classes.containsKey(memberOwner.name)) {
      return false;
    }
    requested.access = widened(requested.access);
    memberOwner.access = widened(memberOwner.access);
    if (member instanceof MethodNode) {
      ((MethodNode) member).access = widened(((MethodNode) member).access);
    } else if (member instanceof FieldNode) {
      ((FieldNode) member).access = widened(((FieldNode) member).access);
    }
    return true;
  }

  private static int widened(int access) {
    return (access & ~(ACC_PRIVATE | ACC_PROTECTED)) | ACC_PUBLIC;
  }

  private boolean isAccessible(ClassNode caller, ClassNode requested, ClassNode memberOwner, int access) {
    if ((access & ACC_PRIVATE) != 0) {
      return caller.name.equals(memberOwner.name);
    }
    boolean classesOk = classAccessible(caller, requested) && classAccessible(caller, memberOwner);
    if ((access & ACC_PUBLIC) != 0) {
      return classesOk;
    }
    return samePackage(caller.name, memberOwner.name) && classesOk;
  }

  private static boolean classAccessible(ClassNode caller, ClassNode target) {
    return (target.access & ACC_PUBLIC) != 0 || samePackage(caller.name, target.name);
  }

  private static boolean samePackage(String a, String b) {
    int ia = a.lastIndexOf('/');
    int ib = b.lastIndexOf('/');
    String pa = ia < 0 ? "" : a.substring(0, ia);
    String pb = ib < 0 ? "" : b.substring(0, ib);
    return pa.equals(pb);
  }

  private static boolean isCall(MethodInsnNode call, String owner, String name, String desc, int opcode) {
    return call.getOpcode() == opcode && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc);
  }

  private static boolean detached(InsnList list, AbstractInsnNode node) {
    return node.getPrevious() == null && node.getNext() == null && list.size() > 1;
  }

  private static Set<LabelNode> jumpTargets(MethodNode method) {
    Set<LabelNode> targets = new HashSet<>();
    for (AbstractInsnNode insn : method.instructions) {
      if (insn instanceof JumpInsnNode) {
        targets.add(((JumpInsnNode) insn).label);
      } else if (insn instanceof TableSwitchInsnNode) {
        TableSwitchInsnNode table = (TableSwitchInsnNode) insn;
        targets.add(table.dflt);
        targets.addAll(table.labels);
      } else if (insn instanceof LookupSwitchInsnNode) {
        LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) insn;
        targets.add(lookup.dflt);
        targets.addAll(lookup.labels);
      }
    }
    if (method.tryCatchBlocks != null) {
      for (TryCatchBlockNode block : method.tryCatchBlocks) {
        targets.add(block.start);
        targets.add(block.end);
        targets.add(block.handler);
      }
    }
    return targets;
  }

  private List<Slice> operands(MethodInsnNode call, int count, Set<LabelNode> targets) {
    Slice[] slices = new Slice[count];
    AbstractInsnNode cursor = call.getPrevious();
    for (int i = count - 1; i >= 0; i--) {
      AbstractInsnNode start = sliceStart(cursor, targets);
      if (start == null) {
        return null;
      }
      Slice slice = new Slice();
      slice.start = start;
      slice.end = cursor;
      for (AbstractInsnNode n = start; ; n = n.getNext()) {
        if (n.getOpcode() >= 0) {
          slice.insns.add(n);
        }
        if (n == cursor) {
          break;
        }
      }
      if (slice.insns.isEmpty()) {
        return null;
      }
      slices[i] = slice;
      cursor = start.getPrevious();
    }
    return Arrays.asList(slices);
  }

  private AbstractInsnNode sliceStart(AbstractInsnNode last, Set<LabelNode> targets) {
    int need = 1;
    AbstractInsnNode cur = last;
    while (cur != null) {
      if (cur.getOpcode() < 0) {
        if (cur instanceof LabelNode && targets.contains(cur)) {
          return null;
        }
        cur = cur.getPrevious();
        continue;
      }
      int[] effect = effect(cur);
      if (effect == null) {
        return null;
      }
      need = need - effect[1] + effect[0];
      if (need < 0) {
        return null;
      }
      if (need == 0) {
        return effect[0] == 0 ? cur : null;
      }
      cur = cur.getPrevious();
    }
    return null;
  }

  private static int[] effect(AbstractInsnNode node) {
    int op = node.getOpcode();
    if (op == NOP) {
      return new int[] {0, 0};
    }
    if (op >= ACONST_NULL && op <= SIPUSH || op == LDC) {
      return new int[] {0, 1};
    }
    if (op >= ILOAD && op <= ALOAD) {
      return new int[] {0, 1};
    }
    if (op >= IALOAD && op <= SALOAD) {
      return new int[] {2, 1};
    }
    if (op >= IASTORE && op <= SASTORE) {
      return new int[] {3, 0};
    }
    if (op == POP) {
      return new int[] {1, 0};
    }
    if (op == DUP) {
      return new int[] {1, 2};
    }
    if (op >= IADD && op <= DREM) {
      return new int[] {2, 1};
    }
    if (op >= INEG && op <= DNEG) {
      return new int[] {1, 1};
    }
    if (op >= ISHL && op <= LXOR) {
      return new int[] {2, 1};
    }
    if (op >= I2L && op <= I2S) {
      return new int[] {1, 1};
    }
    if (op >= LCMP && op <= DCMPG) {
      return new int[] {2, 1};
    }
    switch (op) {
      case GETSTATIC:
        return new int[] {0, 1};
      case PUTSTATIC:
        return new int[] {1, 0};
      case GETFIELD:
        return new int[] {1, 1};
      case PUTFIELD:
        return new int[] {2, 0};
      case NEW:
        return new int[] {0, 1};
      case NEWARRAY:
      case ANEWARRAY:
      case ARRAYLENGTH:
      case CHECKCAST:
      case INSTANCEOF:
        return new int[] {1, 1};
      case MULTIANEWARRAY:
        return new int[] {((org.objectweb.asm.tree.MultiANewArrayInsnNode) node).dims, 1};
      case INVOKEVIRTUAL:
      case INVOKESPECIAL:
      case INVOKESTATIC:
      case INVOKEINTERFACE: {
        MethodInsnNode m = (MethodInsnNode) node;
        int pops = Type.getArgumentTypes(m.desc).length + (op == INVOKESTATIC ? 0 : 1);
        int pushes = Type.getReturnType(m.desc).getSort() == Type.VOID ? 0 : 1;
        return new int[] {pops, pushes};
      }
      case INVOKEDYNAMIC: {
        org.objectweb.asm.tree.InvokeDynamicInsnNode d = (org.objectweb.asm.tree.InvokeDynamicInsnNode) node;
        int pops = Type.getArgumentTypes(d.desc).length;
        int pushes = Type.getReturnType(d.desc).getSort() == Type.VOID ? 0 : 1;
        return new int[] {pops, pushes};
      }
      default:
        return null;
    }
  }

  private void moveInto(InsnList target, List<AbstractInsnNode> nodes) {
    for (AbstractInsnNode node : nodes) {
      if (node.getPrevious() != null || node.getNext() != null) {
        currentList.remove(node);
      }
      moved.add(node);
      target.add(node);
    }
  }

  private List<AbstractInsnNode> range(AbstractInsnNode start, MethodInsnNode last) {
    moved.clear();
    List<AbstractInsnNode> nodes = new ArrayList<>();
    for (AbstractInsnNode n = start; n != last; n = n.getNext()) {
      if (n.getOpcode() >= 0) {
        nodes.add(n);
      }
    }
    return nodes;
  }

  private void replace(MethodNode method, MethodInsnNode last, List<AbstractInsnNode> range, InsnList replacement) {
    method.instructions.insertBefore(last, replacement);
    for (AbstractInsnNode n : range) {
      if (!moved.contains(n)) {
        method.instructions.remove(n);
      }
    }
    method.instructions.remove(last);
  }

  private static class Slice {
    AbstractInsnNode start;
    AbstractInsnNode end;
    final List<AbstractInsnNode> insns = new ArrayList<>();
  }

  private static class Lookup {
    List<Slice> operands;
    boolean declared;
  }

  private static class Found {
    ClassNode owner;
    MethodNode method;
  }

  private static class FieldTarget {
    ClassNode requested;
    ClassNode memberOwner;
    FieldNode field;
  }

  public static class Config extends TransformerConfig {

    private boolean widenAccess;

    public Config() {
      this(ReflectionResolverTransformer.class);
    }

    protected Config(Class<? extends Transformer<?>> implementation) {
      super(implementation);
    }

    public boolean isWidenAccess() {
      return widenAccess;
    }

    public void setWidenAccess(boolean widenAccess) {
      this.widenAccess = widenAccess;
    }
  }
}
