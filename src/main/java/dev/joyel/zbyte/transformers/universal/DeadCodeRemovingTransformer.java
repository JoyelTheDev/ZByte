package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.AsmFlowUtil;

import org.objectweb.asm.tree.*;

import java.util.*;

public class DeadCodeRemovingTransformer extends Transformer<TransformerConfig> {
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
                if (prune(method)) {
                    markChange();
                }
            }
        }
    }

    public static boolean prune(MethodNode method) {
        InsnList instructions = method.instructions;
        if (instructions == null || instructions.size() == 0)
            return false;

        List<TryCatchBlockNode> tryCatches = method.tryCatchBlocks;
        Set<AbstractInsnNode> protectedLabels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TryCatchBlockNode block : tryCatches) {
            protectedLabels.add(block.start);
            protectedLabels.add(block.end);
            protectedLabels.add(block.handler);
        }

        boolean dirty = false;

        Set<AbstractInsnNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<AbstractInsnNode> flowStarts = new ArrayList<>(tryCatches.size() + 1);
        flowStarts.add(instructions.getFirst());
        for (TryCatchBlockNode tryCatch : tryCatches)
            flowStarts.add(tryCatch.handler);
        visit(visited, flowStarts);

        int end = instructions.size() - 1;
        int[] keptPrefix = new int[end + 2];
        Map<LabelNode, Integer> labelIndices = new IdentityHashMap<>();
        for (int i = 0; i <= end; i++) {
            AbstractInsnNode insn = instructions.get(i);
            if (insn instanceof LabelNode label)
                labelIndices.put(label, i);
            boolean keep = visited.contains(insn) && insn.getOpcode() != NOP;
            if (!keep && (i == end && insn.getType() == AbstractInsnNode.LABEL
                    || insn.getType() == AbstractInsnNode.LABEL && protectedLabels.contains(insn)))
                keep = true;
            keptPrefix[i + 1] = keptPrefix[i] + (keep && insn.getOpcode() >= 0 ? 1 : 0);
        }

        boolean[] catchHasKeptNode = new boolean[tryCatches.size()];
        for (int i = 0; i < tryCatches.size(); i++) {
            TryCatchBlockNode block = tryCatches.get(i);
            Integer start = labelIndices.get(block.start);
            Integer finish = labelIndices.get(block.end);
            catchHasKeptNode[i] = start == null || finish == null || start >= finish
                    || keptPrefix[finish] > keptPrefix[start + 1];
        }

        for (int i = end; i >= 0; i--) {
            if (i >= instructions.size()) continue;
            AbstractInsnNode insn = instructions.get(i);
            if (insn == null) continue;
            if (!visited.contains(insn) || insn.getOpcode() == NOP) {
                if (i == end && insn.getType() == AbstractInsnNode.LABEL)
                    continue;
                if (insn.getType() == AbstractInsnNode.LABEL && protectedLabels.contains(insn))
                    continue;
                instructions.remove(insn);
                dirty = true;
            }
        }

        List<TryCatchBlockNode> keptTryCatches = new ArrayList<>(tryCatches.size());
        for (int i = 0; i < tryCatches.size(); i++)
            if (catchHasKeptNode[i])
                keptTryCatches.add(tryCatches.get(i));
        if (keptTryCatches.size() != tryCatches.size()) {
            tryCatches.clear();
            tryCatches.addAll(keptTryCatches);
            dirty = true;
        }

        List<LocalVariableNode> variables = method.localVariables;
        if (variables != null && variables.stream().anyMatch(l ->
                !instructions.contains(l.start) || !instructions.contains(l.end))) {
            AsmFlowUtil.fixMissingVariableLabels(method);
            dirty = true;
        }

        return dirty;
    }

    private static void visit(Set<AbstractInsnNode> visited, List<AbstractInsnNode> startingPoints) {
        Queue<AbstractInsnNode> todo = new ArrayDeque<>(startingPoints);
        while (!todo.isEmpty()) {
            AbstractInsnNode insn = todo.remove();
            while (insn != null && visited.add(insn)) {
                handleNext: {
                    switch (insn.getType()) {
                        case AbstractInsnNode.INSN -> {
                            if (AsmFlowUtil.isTerminalOrAlwaysTakeFlowControl(insn.getOpcode()))
                                break handleNext;
                        }
                        case AbstractInsnNode.JUMP_INSN -> {
                            JumpInsnNode jin = (JumpInsnNode) insn;
                            todo.add(jin.label);
                            if (AsmFlowUtil.isTerminalOrAlwaysTakeFlowControl(jin.getOpcode()))
                                break handleNext;
                        }
                        case AbstractInsnNode.TABLESWITCH_INSN -> {
                            TableSwitchInsnNode tsin = (TableSwitchInsnNode) insn;
                            todo.add(tsin.dflt);
                            todo.addAll(tsin.labels);
                            break handleNext;
                        }
                        case AbstractInsnNode.LOOKUPSWITCH_INSN -> {
                            LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) insn;
                            todo.add(lsin.dflt);
                            todo.addAll(lsin.labels);
                            break handleNext;
                        }
                    }
                    insn = insn.getNext();
                }
            }
        }
    }
}
