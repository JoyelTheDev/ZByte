package dev.joyel.zbyte.analyzer.frame;

import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JumpFrame extends Frame {
    private List<Frame> comparators = new ArrayList<>();
    private transient List<AbstractInsnNode> targets;

    public JumpFrame(int opcode, List<Frame> comparators, AbstractInsnNode... targets) {
        super(opcode);
        this.comparators.addAll(comparators);
        this.targets = Arrays.asList(targets);

        for (Frame comparator : this.comparators) {
            comparator.children.add(this);
        }
    }

    public List<Frame> getComparators() {
        return comparators;
    }

    public List<AbstractInsnNode> getTargets() {
        return targets;
    }
}
