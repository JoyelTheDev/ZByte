package dev.joyel.zbyte.analysis;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClassMapTypeHierarchy implements TypeHierarchy {

    private static final String OBJECT = "java/lang/Object";

    private final Map<String, ClassNode> classes;
    private final Map<String, ClassNode> classpath;

    public ClassMapTypeHierarchy(Map<String, ClassNode> classes, Map<String, ClassNode> classpath) {
        this.classes = classes;
        this.classpath = classpath;
    }

    private ClassNode find(String name) {
        ClassNode node = classes.get(name);
        return node != null ? node : classpath.get(name);
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    private List<String> parents(String name) {
        List<String> result = new ArrayList<>();
        ClassNode node = find(name);
        if (node != null) {
            if (node.superName != null) {
                result.add(node.superName);
            }
            if (node.interfaces != null) {
                result.addAll(node.interfaces);
            }
            return result;
        }
        Class<?> type = load(name);
        if (type != null) {
            if (type.getSuperclass() != null) {
                result.add(Type.getInternalName(type.getSuperclass()));
            }
            for (Class<?> iface : type.getInterfaces()) {
                result.add(Type.getInternalName(iface));
            }
        }
        return result;
    }

    @Override
    public boolean isAssignableFrom(String parent, String child) {
        if (parent.equals(child) || OBJECT.equals(parent)) {
            return true;
        }
        return getAssignableSupertypes(child).contains(parent);
    }

    @Override
    public Set<String> getAssignableSupertypes(String child) {
        Set<String> names = new LinkedHashSet<>();
        names.add(child);
        Deque<String> todo = new ArrayDeque<>(parents(child));
        while (!todo.isEmpty()) {
            String current = todo.pop();
            if (names.add(current)) {
                todo.addAll(parents(current));
            }
        }
        names.add(OBJECT);
        return names;
    }

    @Override
    public String getSuperName(String name) {
        ClassNode node = find(name);
        if (node != null) {
            return node.superName;
        }
        Class<?> type = load(name);
        return type == null || type.getSuperclass() == null ? null : Type.getInternalName(type.getSuperclass());
    }

    @Override
    public boolean isInterface(String name) {
        ClassNode node = find(name);
        if (node != null) {
            return (node.access & Opcodes.ACC_INTERFACE) != 0;
        }
        Class<?> type = load(name);
        return type != null && type.isInterface();
    }
}
