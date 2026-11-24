package dev.joyel.zbyte.analyzer.frame;

import org.objectweb.asm.Opcodes;

public class CheckCastFrame extends Frame {
    private Frame check;

    public CheckCastFrame(Frame check) {
        super(Opcodes.CHECKCAST);
        this.check = check;
        this.check.children.add(this);
        this.parents.add(this.check);
    }

}
