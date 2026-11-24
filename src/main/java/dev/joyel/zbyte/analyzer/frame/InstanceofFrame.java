package dev.joyel.zbyte.analyzer.frame;

import org.objectweb.asm.Opcodes;

public class InstanceofFrame extends Frame {
    private Frame check;

    public InstanceofFrame(Frame check) {
        super(Opcodes.INSTANCEOF);
        this.check = check;
        this.check.children.add(this);
    }
}
