package dev.joyel.zbyte.analyzer.frame;

public class MonitorFrame extends Frame {
    private Frame target;

    public MonitorFrame(int opcode, Frame target) {
        super(opcode);
        this.target = target;
        this.target.children.add(this);
    }
}
