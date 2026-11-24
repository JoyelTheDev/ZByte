package dev.joyel.zbyte.analyzer.frame;

import org.objectweb.asm.Opcodes;

public class NewFrame extends Frame {
    private String ntype;

    public NewFrame(String type) {
        super(Opcodes.NEW);
        this.ntype = type;
    }

    public String getType() {
        return this.ntype;
    }
}
