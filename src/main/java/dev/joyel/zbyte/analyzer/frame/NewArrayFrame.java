package dev.joyel.zbyte.analyzer.frame;

public class NewArrayFrame extends Frame {
    private Frame length;
    private String ntype;

    public NewArrayFrame(int opcode, String type, Frame length) {
        super(opcode);
        this.length = length;
        this.ntype = type;

        this.length.children.add(this);
    }
    
    public Frame getLength() {
        return length;
    }
}
