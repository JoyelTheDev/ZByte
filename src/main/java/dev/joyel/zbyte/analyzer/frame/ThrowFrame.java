package dev.joyel.zbyte.analyzer.frame;

import org.objectweb.asm.Opcodes;

public class ThrowFrame extends Frame {

    private Frame throwable;

    public ThrowFrame(Frame throwable) {
        super(Opcodes.ATHROW);
        this.throwable = throwable;
        this.throwable.children.add(this);
    }
}
