package dev.joyel.zbyte.analyzer.frame;

import org.objectweb.asm.Opcodes;

public class ArrayLengthFrame extends Frame {
    private Frame array;

    public ArrayLengthFrame(Frame array) {
        super(Opcodes.ARRAYLENGTH);
        this.array = array;
        this.array.children.add(this);
        this.parents.add(array);
    }

}
