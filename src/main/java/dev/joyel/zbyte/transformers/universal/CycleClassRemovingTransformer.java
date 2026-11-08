package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CycleClassRemovingTransformer extends Transformer<TransformerConfig> {

    @Override
    public boolean transform() throws Throwable {
        List<String> cyclic = new ArrayList<>();
        for (String name : classes.keySet()) {
            if (reachesItself(name)) {
                cyclic.add(name);
            }
        }
        for (String name : cyclic) {
            classes.remove(name);
            classpath.remove(name);
        }
        return !cyclic.isEmpty();
    }

    private boolean reachesItself(String start) {
        Set<String> visited = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.addAll(parents(start));
        while (!todo.isEmpty()) {
            String current = todo.pop();
            if (current.equals(start)) {
                return true;
            }
            if (visited.add(current)) {
                todo.addAll(parents(current));
            }
        }
        return false;
    }

    private List<String> parents(String name) {
        List<String> result = new ArrayList<>();
        ClassNode node = classes.get(name);
        if (node == null) {
            return result;
        }
        if (node.superName != null) {
            result.add(node.superName);
        }
        if (node.interfaces != null) {
            result.addAll(node.interfaces);
        }
        return result;
    }
}
