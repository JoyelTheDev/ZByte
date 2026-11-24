package dev.joyel.zbyte.analyzer;

public class Value {
    public ValueType type;
    public String desc;

    public Value(ValueType type) {
        this(type, null);
    }

    public Value(ValueType type, String desc) {
        this.type = type;
        this.desc = desc;
    }
}
