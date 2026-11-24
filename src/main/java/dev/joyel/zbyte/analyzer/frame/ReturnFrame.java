package dev.joyel.zbyte.analyzer.frame;

public class ReturnFrame extends Frame {
    private Frame obj;

    public ReturnFrame(int opcode, Frame obj) {
        super(opcode);
        this.obj = obj;
        if (this.obj != null) {
            this.obj.children.add(this);
        }
    }
}
