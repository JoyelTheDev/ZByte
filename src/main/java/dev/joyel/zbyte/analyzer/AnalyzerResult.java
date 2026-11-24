package dev.joyel.zbyte.analyzer;

import dev.joyel.zbyte.analyzer.frame.Frame;
import org.objectweb.asm.tree.AbstractInsnNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalyzerResult {
    public static final AnalyzerResult EMPTY_RESULT;

    static {
        EMPTY_RESULT = new AnalyzerResult();
        EMPTY_RESULT.frames = Collections.emptyMap();
    }

    protected Map<AbstractInsnNode, List<Frame>> frames;
    protected Map<Frame, AbstractInsnNode> mapping;
    private Map<Frame, AbstractInsnNode> reverse;
    protected int maxLocals;
    protected int maxStack;

    public Map<AbstractInsnNode, List<Frame>> getFrames() {
        return frames;
    }

    public AbstractInsnNode getInsnNode(Frame frame) {
        if (reverse == null) {
            reverse = new HashMap<>();
            frames.forEach((key, value) -> value.forEach(f -> reverse.put(f, key)));
        }

        return reverse.get(frame);
    }

    public Map<Frame, AbstractInsnNode> getMapping() {
        if (mapping == null) {
            Map<Frame, AbstractInsnNode> reverseMapping = new HashMap<>();
            frames.entrySet().forEach(ent -> ent.getValue().forEach(frame -> reverseMapping.put(frame, ent.getKey())));
            mapping = reverseMapping;
        }
        return mapping;
    }

    public int getMaxLocals() {
        return maxLocals;
    }
}
