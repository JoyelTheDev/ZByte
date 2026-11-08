package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.AsmFlowUtil;

import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.stream.Collectors;

public class DuplicateCatchMergingTransformer extends Transformer<TransformerConfig> {
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

    private static final int MIN_BLOCK_THRESHOLD = 4;

    private void run() throws Exception {
        for (ClassNode classNode : new ArrayList<>(classNodes())) {
            for (MethodNode method : classNode.methods) {
                InsnList instructions = method.instructions;
                if (instructions == null) continue;
                if (method.tryCatchBlocks == null || method.tryCatchBlocks.size() <= 1) continue;
                if (mergeMethod(method)) {
                    markChange();
                }
            }
        }
    }

    private boolean mergeMethod(MethodNode method) {
        boolean isTransformed = false;
        InsnList instructions = method.instructions;

        Set<AbstractInsnNode> visitedHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<TryCatchBlockNode, CodeBlock> catchBlocks = new IdentityHashMap<>();

        for (TryCatchBlockNode tryCatchBlock : method.tryCatchBlocks) {
            List<AbstractInsnNode> catchBlockInsns = new ArrayList<>();
            AbstractInsnNode insn = tryCatchBlock.handler;

            if (!visitedHandlers.add(insn))
                continue;

            while (insn != null) {
                if (insn.getType() != AbstractInsnNode.FRAME && insn.getOpcode() != NOP)
                    catchBlockInsns.add(insn);
                if (AsmFlowUtil.isReturn(insn) || AsmFlowUtil.isFlowControl(insn))
                    break;
                insn = insn.getNext();
            }

            if (catchBlockInsns.size() < MIN_BLOCK_THRESHOLD)
                continue;

            if (hasExternalFlowIntoCatchBlock(method, catchBlockInsns))
                continue;

            catchBlocks.put(tryCatchBlock, new CodeBlock(catchBlockInsns, instructions));
        }

        List<CodeBlock> blocks = catchBlocks.values().stream()
                .sorted()
                .toList();

        for (int i = 0; i < blocks.size() - 1; i++) {
            CodeBlock block = blocks.get(i);
            for (int j = blocks.size() - 1; j > i; j--) {
                CodeBlock laterBlock = blocks.get(j);
                if (block.equals(laterBlock)) {
                    block.pruneContent(instructions);
                    block.redirectTo(instructions, (LabelNode) laterBlock.instructions.getFirst());
                    isTransformed = true;
                    break;
                }
            }
        }

        return isTransformed;
    }

    private static boolean hasExternalFlowIntoCatchBlock(MethodNode method, List<AbstractInsnNode> block) {
        if (AsmFlowUtil.hasHandlerFlowIntoBlock(method, block, false))
            return true;
        return AsmFlowUtil.hasInboundFlowReferences(method, block);
    }

    private static final class CodeBlock implements Comparable<CodeBlock> {
        private final List<AbstractInsnNode> instructions;
        private final String disassembled;
        private final int index;

        private CodeBlock(List<AbstractInsnNode> instructions, InsnList methodInstructions) {
            this.instructions = instructions;

            Map<LabelNode, Integer> blockLabels = new IdentityHashMap<>();
            for (AbstractInsnNode insn : instructions)
                if (insn instanceof LabelNode label)
                    blockLabels.put(label, blockLabels.size());

            this.disassembled = instructions.stream()
                    .skip(1)
                    .map(insn -> describe(insn, blockLabels, methodInstructions))
                    .collect(Collectors.joining("\n"));

            this.index = methodInstructions.indexOf(instructions.get(0));
        }

        private static String describe(AbstractInsnNode insn, Map<LabelNode, Integer> blockLabels, InsnList methodInstructions) {
            return switch (insn) {
                case LabelNode label -> "label " + describeLabel(label, blockLabels, methodInstructions);
                case JumpInsnNode jump -> jump.getOpcode() + " " + describeLabel(jump.label, blockLabels, methodInstructions);
                case TableSwitchInsnNode tsw -> "tableswitch " + tsw.min + " " + tsw.max
                        + " default " + describeLabel(tsw.dflt, blockLabels, methodInstructions)
                        + tsw.labels.stream().map(l -> " " + describeLabel(l, blockLabels, methodInstructions)).collect(Collectors.joining());
                case LookupSwitchInsnNode lsw -> {
                    StringBuilder sb = new StringBuilder("lookupswitch default " + describeLabel(lsw.dflt, blockLabels, methodInstructions));
                    for (int i = 0; i < lsw.keys.size(); i++)
                        sb.append(' ').append(lsw.keys.get(i)).append(' ').append(describeLabel(lsw.labels.get(i), blockLabels, methodInstructions));
                    yield sb.toString();
                }
                default -> AsmFlowUtil.describeInsn(insn);
            };
        }

        private static String describeLabel(LabelNode label, Map<LabelNode, Integer> blockLabels, InsnList methodInstructions) {
            Integer blockIndex = blockLabels.get(label);
            return blockIndex != null ? "inner-" + blockIndex : "outer-" + methodInstructions.indexOf(label);
        }

        public void pruneContent(InsnList container) {
            while (instructions.size() > 1) {
                AbstractInsnNode next = instructions.remove(1);
                container.remove(next);
            }
        }

        public void redirectTo(InsnList container, LabelNode target) {
            AbstractInsnNode first = instructions.getFirst();
            container.insert(first, new JumpInsnNode(GOTO, target));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CodeBlock codeBlock)) return false;
            return disassembled.equals(codeBlock.disassembled);
        }

        @Override
        public int hashCode() {
            return disassembled.hashCode();
        }

        @Override
        public int compareTo(CodeBlock o) {
            return Integer.compare(index, o.index);
        }
    }
}
