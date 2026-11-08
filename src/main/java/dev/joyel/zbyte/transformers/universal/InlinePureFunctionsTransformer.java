package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@TransformerConfig.ConfigOptions(configClass = InlinePureFunctionsTransformer.Config.class)
public class InlinePureFunctionsTransformer extends Transformer<InlinePureFunctionsTransformer.Config> {

    private static boolean isImpure(int opcode) {
        switch (opcode) {
            case GETFIELD:
            case GETSTATIC:
            case PUTFIELD:
            case PUTSTATIC:
            case INVOKEDYNAMIC:
            case INVOKEINTERFACE:
            case INVOKESPECIAL:
            case INVOKESTATIC:
            case INVOKEVIRTUAL:
            case NEW:
            case MONITORENTER:
            case MONITOREXIT:
            case JSR:
            case RET:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean transform() throws Throwable {
        Map<String, MethodNode> pure = new HashMap<>();
        Map<String, ClassNode> owners = new HashMap<>();
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                if (isPure(methodNode)) {
                    String key = key(classNode.name, methodNode.name, methodNode.desc);
                    pure.put(key, methodNode);
                    owners.put(key, classNode);
                    logger.debug("Detected pure function {}", key);
                }
            }
        }
        if (pure.isEmpty()) {
            return false;
        }
        boolean changed = false;
        int inlined = 0;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    if (insn.getOpcode() != INVOKESTATIC) {
                        continue;
                    }
                    MethodInsnNode call = (MethodInsnNode) insn;
                    MethodNode callee = pure.get(key(call.owner, call.name, call.desc));
                    if (callee == null || callee == methodNode) {
                        continue;
                    }
                    inline(methodNode, call, callee);
                    changed = true;
                    inlined++;
                }
            }
        }
        if (inlined > 0) {
            logger.info("Inlined {} pure function calls", inlined);
        }
        if (changed && getConfig().isRemovePureMethods()) {
            removeUnreferenced(pure, owners);
        }
        return changed;
    }

    private boolean isPure(MethodNode methodNode) {
        if ((methodNode.access & (ACC_STATIC | ACC_ABSTRACT | ACC_NATIVE | ACC_SYNCHRONIZED)) != ACC_STATIC) {
            return false;
        }
        if (methodNode.name.equals("<init>") || methodNode.name.equals("<clinit>")) {
            return false;
        }
        if (methodNode.instructions.size() == 0 || methodNode.instructions.size() > getConfig().getMaxInstructions()) {
            return false;
        }
        if (!methodNode.tryCatchBlocks.isEmpty() || Type.getReturnType(methodNode.desc).getSort() == Type.VOID) {
            return false;
        }
        for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
            if (isImpure(insn.getOpcode())) {
                return false;
            }
        }
        return true;
    }

    private void inline(MethodNode caller, MethodInsnNode call, MethodNode callee) {
        int base = Math.max(caller.maxLocals, UniversalSupport.requiredLocals(caller));
        Type[] arguments = Type.getArgumentTypes(callee.desc);
        int[] slots = new int[arguments.length];
        int offset = 0;
        for (int i = 0; i < arguments.length; i++) {
            slots[i] = base + offset;
            offset += arguments[i].getSize();
        }
        for (int i = arguments.length - 1; i >= 0; i--) {
            caller.instructions.insertBefore(call, new VarInsnNode(arguments[i].getOpcode(ISTORE), slots[i]));
        }

        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : callee.instructions.toArray()) {
            if (insn instanceof LabelNode) {
                labels.put((LabelNode) insn, new LabelNode());
            }
        }
        LabelNode end = new LabelNode();
        for (AbstractInsnNode insn : callee.instructions.toArray()) {
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }
            int opcode = insn.getOpcode();
            if (opcode >= IRETURN && opcode <= RETURN) {
                caller.instructions.insertBefore(call, new JumpInsnNode(GOTO, end));
                continue;
            }
            AbstractInsnNode copy = insn.clone(labels);
            if (copy instanceof VarInsnNode) {
                ((VarInsnNode) copy).var += base;
            } else if (copy instanceof IincInsnNode) {
                ((IincInsnNode) copy).var += base;
            }
            caller.instructions.insertBefore(call, copy);
        }
        caller.instructions.set(call, end);
        caller.maxLocals = base + UniversalSupport.requiredLocals(callee);
        caller.maxStack = caller.maxStack + callee.maxStack;
    }

    private void removeUnreferenced(Map<String, MethodNode> pure, Map<String, ClassNode> owners) {
        Set<String> referenced = new HashSet<>();
        for (ClassNode classNode : classNodes()) {
            for (MethodNode methodNode : classNode.methods) {
                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) insn;
                        referenced.add(key(call.owner, call.name, call.desc));
                    }
                }
            }
        }
        for (Map.Entry<String, MethodNode> entry : pure.entrySet()) {
            if (!referenced.contains(entry.getKey())) {
                owners.get(entry.getKey()).methods.remove(entry.getValue());
            }
        }
    }

    private static String key(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    public static class Config extends TransformerConfig {

        private boolean removePureMethods = true;
        private int maxInstructions = 150;

        public Config() {
            super(InlinePureFunctionsTransformer.class);
        }

        public boolean isRemovePureMethods() {
            return removePureMethods;
        }

        public void setRemovePureMethods(boolean removePureMethods) {
            this.removePureMethods = removePureMethods;
        }

        public int getMaxInstructions() {
            return maxInstructions;
        }

        public void setMaxInstructions(int maxInstructions) {
            this.maxInstructions = maxInstructions;
        }
    }
}
