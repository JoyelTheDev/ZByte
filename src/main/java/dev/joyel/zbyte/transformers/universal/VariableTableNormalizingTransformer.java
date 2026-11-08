package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.AsmFlowUtil;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class VariableTableNormalizingTransformer extends Transformer<TransformerConfig> {
    private boolean changed;

    private void markChange() {
        changed = true;
    }

    @Override
    public boolean transform() throws Throwable {
        changed = false;
        run();
        return changed;
    }

    private void run() throws Exception {
        for (ClassNode classNode : new ArrayList<>(classNodes())) {
            for (MethodNode method : classNode.methods) {
                if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;

                Type[] argumentTypes = Type.getMethodType(method.desc).getArgumentTypes();
                boolean isStatic = (method.access & ACC_STATIC) != 0;
                int slot = isStatic ? 0 : 1;

                InsnList instructions = method.instructions;
                if (instructions == null) {
                    List<ParameterNode> parameters = new ArrayList<>(argumentTypes.length);
                    for (Type argumentType : argumentTypes) {
                        parameters.add(new ParameterNode("param" + slot, 0));
                        slot += argumentType.getSize();
                    }
                    if (!Objects.equals(parameters, method.parameters)) {
                        method.parameters = parameters;
                        method.localVariables = null;
                        markChange();
                    }
                } else {
                    boolean compacted = compactLocalSlots(method, argumentTypes, isStatic);
                    if (compacted) {
                        method.localVariables = null;
                        markChange();
                    }

                    int normalizedMax = normalizedMaxLocals(method, argumentTypes, isStatic);
                    if (method.maxLocals != normalizedMax) {
                        method.maxLocals = normalizedMax;
                        markChange();
                    }

                    if (!hasNonParameterUsage(method, isStatic, argumentTypes)
                            && hasNonParameterMetadata(method, isStatic, argumentTypes)) {
                        method.localVariables = null;
                        markChange();
                    }

                    LabelNode start = new LabelNode();
                    LabelNode end = new LabelNode();
                    method.instructions.insert(start);
                    method.instructions.add(end);

                    Map<Integer, NameType> slotToVar = new TreeMap<>();
                    if (!isStatic) {
                        slotToVar.put(0, new NameType("this", Type.getObjectType(classNode.name)));
                    }
                    for (Type argumentType : argumentTypes) {
                        slotToVar.put(slot, new NameType("param" + slot, argumentType));
                        slot += argumentType.getSize();
                    }
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof VarInsnNode varInsn) {
                            int varSlot = varInsn.var;
                            slotToVar.computeIfAbsent(varSlot, v -> {
                                Type varType = AsmFlowUtil.getTypeForVarInsn(varInsn);
                                return new NameType("v" + v, varType);
                            });
                        }
                    }

                    List<NameType> newNameTypes = slotToVar.values().stream().toList();
                    List<NameType> existingNameTypes = method.localVariables == null
                            ? Collections.emptyList()
                            : method.localVariables.stream()
                                    .filter(l -> isValidDesc(l.desc))
                                    .map(l -> new NameType(l.name, Type.getType(l.desc)))
                                    .toList();
                    if (!Objects.equals(newNameTypes, existingNameTypes)) {
                        List<LocalVariableNode> variables = slotToVar.entrySet().stream()
                                .map(e -> new LocalVariableNode(
                                        e.getValue().name(),
                                        e.getValue().type().getDescriptor(),
                                        null,
                                        start,
                                        end,
                                        e.getKey()))
                                .toList();
                        method.parameters = null;
                        method.localVariables = variables;
                        markChange();
                    }
                }
            }
        }
    }

    private static boolean hasNonParameterUsage(MethodNode method, boolean isStatic, Type[] argumentTypes) {
        int paramEnd = parameterEndSlot(isStatic, argumentTypes);
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode v && v.var >= paramEnd) return true;
            if (insn instanceof IincInsnNode ii && ii.var >= paramEnd) return true;
        }
        return false;
    }

    private static boolean hasNonParameterMetadata(MethodNode method, boolean isStatic, Type[] argumentTypes) {
        if (method.localVariables == null) return false;
        int paramEnd = parameterEndSlot(isStatic, argumentTypes);
        for (LocalVariableNode v : method.localVariables)
            if (v.index >= paramEnd) return true;
        return false;
    }

    private static int normalizedMaxLocals(MethodNode method, Type[] argumentTypes, boolean isStatic) {
        int maxLocals = parameterEndSlot(isStatic, argumentTypes);
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode v)
                maxLocals = Math.max(maxLocals, v.var + AsmFlowUtil.getTypeForVarInsn(v).getSize());
            else if (insn instanceof IincInsnNode ii)
                maxLocals = Math.max(maxLocals, ii.var + 1);
        }
        return maxLocals;
    }

    private static boolean compactLocalSlots(MethodNode method, Type[] argumentTypes, boolean isStatic) {
        int paramEnd = parameterEndSlot(isStatic, argumentTypes);
        Map<Integer, Integer> remapping = new TreeMap<>();
        for (AbstractInsnNode insn : method.instructions) {
            int slot, width;
            if (insn instanceof VarInsnNode v) {
                slot = v.var;
                width = AsmFlowUtil.getTypeForVarInsn(v).getSize();
            } else if (insn instanceof IincInsnNode ii) {
                slot = ii.var;
                width = 1;
            } else {
                continue;
            }
            for (int offset = 0; offset < width; offset++) {
                int original = slot + offset;
                if (original >= paramEnd) remapping.putIfAbsent(original, 0);
            }
        }
        if (remapping.isEmpty()) return false;
        int nextSlot = paramEnd;
        for (Integer original : remapping.keySet()) remapping.put(original, nextSlot++);
        boolean dirty = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode v && v.var >= paramEnd) {
                Integer replacement = remapping.get(v.var);
                if (replacement != null && replacement != v.var) {
                    v.var = replacement;
                    dirty = true;
                }
            } else if (insn instanceof IincInsnNode ii && ii.var >= paramEnd) {
                Integer replacement = remapping.get(ii.var);
                if (replacement != null && replacement != ii.var) {
                    ii.var = replacement;
                    dirty = true;
                }
            }
        }
        return dirty;
    }

    private static int parameterEndSlot(boolean isStatic, Type[] argumentTypes) {
        int slot = isStatic ? 0 : 1;
        for (Type t : argumentTypes) slot += t.getSize();
        return slot;
    }

    private static boolean isValidDesc(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        try {
            Type type = Type.getType(desc);
            return type.getSort() != Type.METHOD && type.getSort() != Type.VOID;
        } catch (Exception e) {
            return false;
        }
    }

    private record NameType(String name, Type type) {
    }
}
