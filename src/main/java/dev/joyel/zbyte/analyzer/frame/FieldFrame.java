package dev.joyel.zbyte.analyzer.frame;

public class FieldFrame extends Frame {

    private Frame instance;
    private Frame obj;
    private String owner;
    private String name;
    private String desc;

    public FieldFrame(int opcode, String owner, String name, String desc, Frame instance, Frame obj) {
        super(opcode);
        this.instance = instance;
        this.obj = obj;
        this.owner = owner;
        this.name = name;
        this.desc = desc;

        if (this.instance != null) {
            this.instance.children.add(this);
        }
        if (this.obj != null) {
            this.obj.children.add(this);
        }
    }
    
    public String getDesc() {
        return desc;
    }

    public String getName() {
        return name;
    }

    public String getOwner() {
        return owner;
    }

    public Frame getInstance() {
        return instance;
    }

    public Frame getObj() {
        return obj;
    }
}
