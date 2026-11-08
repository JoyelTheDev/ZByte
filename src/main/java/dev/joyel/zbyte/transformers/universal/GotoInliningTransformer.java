package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import dev.joyel.zbyte.utils.AsmFlowUtil;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class GotoInliningTransformer extends Transformer<TransformerConfig> {
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
            for (int m = 0; m < classNode.methods.size(); m++) {
                MethodNode base = classNode.methods.get(m);
                if (base.instructions == null)
                    continue;

                MethodNode method = copyOf(base);
                InsnList instructions = method.instructions;

                DeadCodeRemovingTransformer.prune(method);

                VisitCounters visitCounters = new VisitCounters();
                for (int i = 0; i < instructions.size(); i++) {
                    AbstractInsnNode insn = instructions.get(i);
                    if (insn instanceof JumpInsnNode cast) {
                        visitCounters.of(cast.label).addExplicitSource(cast);
                    } else if (insn instanceof TableSwitchInsnNode cast) {
                        visitCounters.of(cast.dflt).addExplicitSource(cast);
                        cast.labels.forEach(l -> visitCounters.of(l).addExplicitSource(cast));
                    } else if (insn instanceof LookupSwitchInsnNode cast) {
                        visitCounters.of(cast.dflt).addExplicitSource(cast);
                        cast.labels.forEach(l -> visitCounters.of(l).addExplicitSource(cast));
                    }
                }

                if (method.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tryCatch : method.tryCatchBlocks) {
                        visitCounters.of(tryCatch.start).markTryStart();
                        visitCounters.of(tryCatch.end).markTryEnd();
                        visitCounters.of(tryCatch.handler).markTryHandler();
                    }
                }

                for (int i = 0; i < instructions.size(); i++) {
                    AbstractInsnNode insn = instructions.get(i);
                    if (insn instanceof LabelNode targetLabel) {
                        AbstractInsnNode prev = targetLabel.getPrevious();
                        if (prev == null) {
                            visitCounters.of(targetLabel).markImplicitFlow();
                            continue;
                        }
                        while (true) {
                            if (AsmFlowUtil.isTerminalOrAlwaysTakeFlowControl(prev.getOpcode()))
                                break;
                            if (prev instanceof LabelNode prevLabel && visitCounters.of(prevLabel).isVisited()) {
                                visitCounters.of(targetLabel).markImplicitFlow();
                                break;
                            }
                            prev = prev.getPrevious();
                            if (prev == null) {
                                visitCounters.of(targetLabel).markImplicitFlow();
                                break;
                            }
                        }
                    }
                }

                boolean localDirty = false;
                for (int i = 0; i < instructions.size(); i++) {
                    AbstractInsnNode insn = instructions.get(i);
                    if (insn.getOpcode() != GOTO)
                        continue;
                    JumpInsnNode jin = (JumpInsnNode) insn;
                    VisitCounter counter = visitCounters.of(jin.label);
                    if (jin.label == jin.getNext() && !counter.isTryTarget()) {
                        localDirty = true;
                        instructions.set(jin, new InsnNode(NOP));
                        counter.removeExplicitSource(jin);
                        counter.markImplicitFlow();
                    }
                }

                for (int i = 0; i < instructions.size(); i++) {
                    AbstractInsnNode insn = instructions.get(i);
                    if (insn.getOpcode() != GOTO)
                        continue;
                    JumpInsnNode jin = (JumpInsnNode) insn;
                    if (visitCounters.of(jin.label).count() > 1)
                        continue;

                    doInline:
                    {
                        List<AbstractInsnNode> block = new ArrayList<>();
                        AbstractInsnNode target = jin.label;
                        while (target != null) {
                            if (target == jin)
                                break doInline;

                            if (method.tryCatchBlocks != null) {
                                for (TryCatchBlockNode tryCatchBlock : method.tryCatchBlocks) {
                                    int gotoIndex = instructions.indexOf(jin);
                                    int targetIndex = instructions.indexOf(target);
                                    int tryStart = instructions.indexOf(tryCatchBlock.start);
                                    int tryEnd = instructions.indexOf(tryCatchBlock.end);

                                    if (tryStart <= targetIndex && targetIndex < tryEnd) {
                                        if (tryStart > gotoIndex || gotoIndex >= tryEnd)
                                            break doInline;
                                    }
                                    if (tryStart <= gotoIndex && gotoIndex < tryEnd) {
                                        if (tryStart > targetIndex || targetIndex >= tryEnd)
                                            break doInline;
                                    }
                                }
                            }

                            int targetOp = target.getOpcode();
                            if (target.getType() != AbstractInsnNode.FRAME)
                                block.add(target);

                            if (isGotoBlockTerminator(targetOp)) {
                                AbstractInsnNode next = AsmFlowUtil.getNextInsn(target);
                                if (instructions.getLast() == next || next == null) {
                                    AbstractInsnNode prevBeforeBlock = AsmFlowUtil.getPreviousInsn(jin.label);
                                    if (prevBeforeBlock != null
                                            && !AsmFlowUtil.isTerminalOrAlwaysTakeFlowControl(prevBeforeBlock.getOpcode()))
                                        break doInline;
                                }

                                for (AbstractInsnNode blockInsn : block) {
                                    int blockInsnOp = blockInsn.getOpcode();
                                    if (blockInsn != jin.label && blockInsnOp == -1
                                            && blockInsn instanceof LabelNode blockInsnLabel
                                            && visitCounters.of(blockInsnLabel).getExplicitFlowSourcesExcluding(block) > 1)
                                        break doInline;
                                }

                                break;
                            }

                            target = target.getNext();
                        }

                        InsnList tempInsnList = new InsnList();
                        for (AbstractInsnNode blockInsn : block) {
                            instructions.remove(blockInsn);
                            tempInsnList.add(blockInsn);
                        }

                        instructions.insert(jin, tempInsnList);
                        instructions.remove(jin);
                        localDirty = true;

                        visitCounters.of(jin.label).removeExplicitSource(jin);

                        i = 0;
                    }
                }

                if (localDirty) {
                    DeadCodeRemovingTransformer.prune(method);
                    AsmFlowUtil.fixMissingVariableLabels(method);
                    markChange();
                    classNode.methods.set(m, method);
                }
            }
        }
    }

    private static boolean isGotoBlockTerminator(int op) {
        return AsmFlowUtil.isReturn(op)
                || op == GOTO
                || op == TABLESWITCH
                || op == LOOKUPSWITCH
                || op == ATHROW;
    }

    private static MethodNode copyOf(MethodNode base) {
        MethodNode copy = new MethodNode(
                Opcodes.ASM9,
                base.access,
                base.name,
                base.desc,
                base.signature,
                base.exceptions.toArray(String[]::new)
        ) {
            @Override
            public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            }
        };
        base.accept(copy);
        return copy;
    }

    private static class VisitCounters {
        private final Map<LabelNode, VisitCounter> visitCounters = new IdentityHashMap<>();

        public VisitCounter of(LabelNode label) {
            return visitCounters.computeIfAbsent(label, VisitCounter::new);
        }
    }

    private static class VisitCounter {
        private final LabelNode label;
        private final Set<AbstractInsnNode> explicitFlowSources =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean implicitFlow;
        private boolean tryTarget;

        private VisitCounter(LabelNode label) {
            this.label = label;
        }

        public void addExplicitSource(AbstractInsnNode insn) {
            explicitFlowSources.add(insn);
        }

        public void removeExplicitSource(AbstractInsnNode insn) {
            explicitFlowSources.remove(insn);
        }

        public void markImplicitFlow() {
            implicitFlow = true;
        }

        public boolean isVisited() {
            if (!explicitFlowSources.isEmpty())
                return true;
            return implicitFlow || tryTarget;
        }

        public boolean isTryTarget() {
            return tryTarget;
        }

        public long getExplicitFlowSourcesExcluding(Collection<AbstractInsnNode> block) {
            return explicitFlowSources.stream()
                    .filter(insn -> !block.contains(insn))
                    .count();
        }

        public int count() {
            return explicitFlowSources.size() + (implicitFlow ? 1 : 0) + (tryTarget ? 1 : 0);
        }

        public void markTryStart() {
            tryTarget = true;
        }

        public void markTryEnd() {
            tryTarget = true;
        }

        public void markTryHandler() {
            tryTarget = true;
        }
    }
}
