package dev.joyel.zbyte.analyzer.frame;

import org.objectweb.asm.Opcodes;

import java.util.List;

public class MultiANewArrayFrame extends Frame {
    private List<Frame> dims;

    public MultiANewArrayFrame(List<Frame> dims) {
        super(Opcodes.MULTIANEWARRAY);
        this.dims = dims;
        for (Frame dim : this.dims) {
            dim.children.add(this);
        }
    }
}
