package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.analysis.AnalysisSupport;
import dev.joyel.zbyte.analysis.ClassEvaluator;
import dev.joyel.zbyte.analysis.TypeHierarchy;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashMap;
import java.util.Map;

public final class IndyContext {

    private final Map<String, ClassNode> classes;
    private final Map<String, ClassNode> classpath;
    private final AnalysisSupport analysis;
    private final int maxSteps;
    private final Map<String, ClassNode> runtimeClasses = new HashMap<>();

    public IndyContext(Map<String, ClassNode> classes, Map<String, ClassNode> classpath, int maxSteps) {
        this.classes = classes;
        this.classpath = classpath;
        this.analysis = new AnalysisSupport(classes, classpath);
        this.maxSteps = maxSteps;
    }

    public Map<String, ClassNode> classes() {
        return classes;
    }

    public Map<String, ClassNode> classpath() {
        return classpath;
    }

    public ClassNode findClass(String name) {
        ClassNode node = classes.get(name);
        if (node == null) {
            node = classpath.get(name);
        }
        return node != null ? node : findRuntimeClass(name);
    }

    private synchronized ClassNode findRuntimeClass(String name) {
        if (runtimeClasses.containsKey(name)) {
            return runtimeClasses.get(name);
        }
        ClassNode loaded = UniversalSupport.readRuntimeClass(name);
        runtimeClasses.put(name, loaded);
        return loaded;
    }

    public AnalysisSupport analysis() {
        return analysis;
    }

    public TypeHierarchy hierarchy() {
        return analysis.hierarchy();
    }

    public int maxSteps() {
        return maxSteps;
    }

    public ClassEvaluator newEvaluator() {
        return new ClassEvaluator(classes);
    }
}
