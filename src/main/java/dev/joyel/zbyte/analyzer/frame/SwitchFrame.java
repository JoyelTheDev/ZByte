package dev.joyel.zbyte.analyzer.frame;

import org.objectweb.asm.tree.LabelNode;

import java.util.ArrayList;
import java.util.List;

public class SwitchFrame extends Frame {
    private Frame switchTarget;
    private List<LabelNode> nodes = new ArrayList<>();

    public SwitchFrame(int opcode, Frame switchTarget, List<LabelNode> nodes, LabelNode dflt) {
        super(opcode);
        this.switchTarget = switchTarget;
        this.nodes.addAll(nodes);
        this.nodes.add(dflt);

        this.switchTarget.children.add(this);
    }

    public Frame getSwitchTarget() {
        return this.switchTarget;
    }
}
