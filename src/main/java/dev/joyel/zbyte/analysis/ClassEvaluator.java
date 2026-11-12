package dev.joyel.zbyte.analysis;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.defined.DictionaryMethodProvider;
import dev.joyel.zbyte.executor.defined.JVMComparisonProvider;
import dev.joyel.zbyte.executor.defined.JVMMethodProvider;
import dev.joyel.zbyte.executor.defined.MappedFieldProvider;
import dev.joyel.zbyte.executor.defined.PrimitiveFieldProvider;
import dev.joyel.zbyte.executor.providers.DelegatingProvider;
import dev.joyel.zbyte.executor.values.JavaBoolean;
import dev.joyel.zbyte.executor.values.JavaByte;
import dev.joyel.zbyte.executor.values.JavaCharacter;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaFloat;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaLong;
import dev.joyel.zbyte.executor.values.JavaObject;
import dev.joyel.zbyte.executor.values.JavaShort;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClassEvaluator {

    private final Map<String, ClassNode> classes;
    private final DelegatingProvider provider = new DelegatingProvider();
    private final MappedFieldProvider fields = new MappedFieldProvider();
    private final Set<String> initialized = new HashSet<>();

    public ClassEvaluator(Map<String, ClassNode> classes) {
        this.classes = classes;
        provider.register(new JVMMethodProvider());
        provider.register(new JVMComparisonProvider());
        provider.register(new HostComparisonProvider());
        provider.register(fields);
        provider.register(new PrimitiveFieldProvider());
        provider.register(new DictionaryMethodProvider(classes));
    }

    public boolean initialize(String owner) {
        if (!initialized.add(owner)) {
            return true;
        }
        ClassNode classNode = classes.get(owner);
        if (classNode == null) {
            return false;
        }
        for (MethodNode method : classNode.methods) {
            if (method.name.equals("<clinit>")) {
                Context context = newContext(owner, method.name);
                try {
                    MethodExecutor.execute(classNode, method, new ArrayList<>(), null, context);
                } catch (Throwable t) {
                    return false;
                }
            }
        }
        return true;
    }

    public Object invokeStatic(String owner, String name, String desc, Object... arguments) throws Throwable {
        ClassNode classNode = classes.get(owner);
        if (classNode == null) {
            throw new IllegalArgumentException("Unknown class " + owner);
        }
        MethodNode method = null;
        for (MethodNode candidate : classNode.methods) {
            if (candidate.name.equals(name) && candidate.desc.equals(desc)) {
                method = candidate;
            }
        }
        if (method == null) {
            throw new IllegalArgumentException("Unknown method " + owner + "." + name + desc);
        }
        return execute(classNode, method, arguments);
    }

    public Object execute(ClassNode owner, MethodNode method, Object... arguments) throws Throwable {
        Type[] types = Type.getArgumentTypes(method.desc);
        List<JavaValue> values = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            values.add(wrap(types[i], arguments[i]));
        }
        Context context = newContext(owner.name, method.name);
        return MethodExecutor.execute(owner, method, values, null, context);
    }

    public boolean run(ClassNode owner, MethodNode method, Object... arguments) {
        try {
            execute(owner, method, arguments);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Context newContext(String owner, String method) {
        Context context = new Context(provider);
        context.dictionary = classes;
        context.push(owner, method, 0);
        return context;
    }

    public Object getStatic(String owner, String name, String desc) {
        return fields.getField(owner, name, desc, null, null);
    }

    public void putStatic(String owner, String name, String desc, Object value) {
        fields.setField(owner, name, desc, null, value, null);
    }

    private static JavaValue wrap(Type type, Object value) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return new JavaBoolean(value instanceof Boolean ? (Boolean) value : ((Number) value).intValue() != 0);
            case Type.BYTE:
                return new JavaByte(((Number) value).byteValue());
            case Type.CHAR:
                return new JavaCharacter(value instanceof Character ? (Character) value : (char) ((Number) value).intValue());
            case Type.SHORT:
                return new JavaShort(((Number) value).shortValue());
            case Type.INT:
                return new JavaInteger(((Number) value).intValue());
            case Type.FLOAT:
                return new JavaFloat(((Number) value).floatValue());
            case Type.LONG:
                return new JavaLong(((Number) value).longValue());
            case Type.DOUBLE:
                return new JavaDouble(((Number) value).doubleValue());
            default:
                return new JavaObject(value, type.getInternalName());
        }
    }
}
