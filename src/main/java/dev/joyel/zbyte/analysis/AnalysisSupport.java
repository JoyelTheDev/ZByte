package dev.joyel.zbyte.analysis;

import dev.joyel.zbyte.analysis.lookup.BasicLookups;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.transformers.universal.DeadCodeRemovingTransformer;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.Map;

public final class AnalysisSupport {
    private final TypeHierarchy hierarchy;

    public AnalysisSupport(Map<String, ClassNode> classes, Map<String, ClassNode> classpath) {
        this.hierarchy = new ClassMapTypeHierarchy(classes, classpath);
    }

    public TypeHierarchy hierarchy() {
        return hierarchy;
    }

    public ReInterpreter newInterpreter() {
        ReInterpreter interpreter = new ReInterpreter(hierarchy);
        interpreter.setGetStaticLookup(new BasicLookups.GetStatic());
        interpreter.setInvokeStaticLookup(new BasicLookups.InvokeStatic());
        interpreter.setInvokeVirtualLookup(new BasicLookups.InvokeVirtual());
        return interpreter;
    }

    public ReAnalyzer newAnalyzer(ClassNode cls, MethodNode method) {
        return new ReAnalyzer(newInterpreter());
    }

    public Frame<ReValue>[] analyze(ClassNode cls, MethodNode method) throws TransformationException {
        try {
            return newAnalyzer(cls, method).analyze(cls.name, method);
        } catch (Throwable t) {
            throw new TransformationException("Error encountered when computing method frames", t);
        }
    }

    public boolean isAssignable(Type targetType, Type valueType) {
        if (targetType.equals(valueType))
            return true;
        int targetSort = targetType.getSort();
        int valueSort = valueType.getSort();
        if (targetSort == Type.ARRAY || valueSort == Type.ARRAY)
            return targetSort == Type.OBJECT && Types.OBJECT_TYPE.equals(targetType);
        if (targetSort != Type.OBJECT || valueSort != Type.OBJECT)
            return false;
        return hierarchy.isAssignableFrom(targetType.getInternalName(), valueType.getInternalName());
    }

    public boolean pruneDeadCode(ClassNode declaringClass, MethodNode method) throws TransformationException {
        return DeadCodeRemovingTransformer.prune(method);
    }
}
