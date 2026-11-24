package dev.joyel.zbyte.analyzer.frame;

import java.util.Collections;
import java.util.List;

public class MethodFrame extends Frame {

    private Frame instance;
    private List<Frame> args;
    private String owner;
    private String name;
    private String desc;

    public MethodFrame(int opcode, String owner, String name, String desc, Frame instance, List<Frame> args) {
        super(opcode);
        this.instance = instance;
        this.args = args;
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        if (this.instance != null) {
            this.instance.children.add(this);
        }
        for (Frame arg : this.args) {
            arg.children.add(this);
        }
    }

    public List<Frame> getArgs() {
        return Collections.unmodifiableList(args);
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
}
