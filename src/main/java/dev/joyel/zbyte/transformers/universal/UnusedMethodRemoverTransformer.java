package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class UnusedMethodRemoverTransformer extends Transformer<TransformerConfig> {

    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "<init>", "<clinit>", "readObject", "writeObject", "readResolve", "writeReplace", "readObjectNoData"
    ));

    @Override
    public boolean transform() throws Throwable {
        Set<String> called = collectCalled();
        boolean changed = false;
        for (ClassNode classNode : classNodes()) {
            boolean exposedClass = (classNode.access & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
            boolean enumClass = (classNode.access & ACC_ENUM) != 0;
            List<MethodNode> methods = classNode.methods;
            changed |= methods.removeIf(method -> {
                if (RESERVED.contains(method.name) || isMain(method) || (enumClass && isEnumHelper(classNode, method))) {
                    return false;
                }
                if (called.contains(method.name + method.desc)) {
                    return false;
                }
                if (exposedClass && (method.access & (ACC_PUBLIC | ACC_PROTECTED)) != 0) {
                    return false;
                }
                if ((method.access & (ACC_STATIC | ACC_PRIVATE)) == 0 && overridesLibrary(classNode, method)) {
                    return false;
                }
                logger.debug("Removing unused method {}.{}{}", classNode.name, method.name, method.desc);
                return true;
            });
        }
        return changed;
    }

    private Set<String> collectCalled() {
        Set<String> called = new HashSet<>();
        for (ClassNode classNode : classNodes()) {
            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) insn;
                        called.add(call.name + call.desc);
                    } else if (insn instanceof InvokeDynamicInsnNode) {
                        InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                        called.add(indy.bsm.getName() + indy.bsm.getDesc());
                        called.add(indy.name + indy.desc);
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle) {
                                Handle handle = (Handle) arg;
                                called.add(handle.getName() + handle.getDesc());
                            }
                        }
                    }
                }
            }
        }
        return called;
    }

    private static boolean isMain(MethodNode method) {
        return method.name.equals("main")
                && method.desc.equals("([Ljava/lang/String;)V")
                && (method.access & ACC_PUBLIC) != 0
                && (method.access & ACC_STATIC) != 0;
    }

    private static boolean isEnumHelper(ClassNode classNode, MethodNode method) {
        return method.name.equals("values") && method.desc.equals("()[L" + classNode.name + ";")
                || method.name.equals("valueOf") && method.desc.equals("(Ljava/lang/String;)L" + classNode.name + ";");
    }

    private boolean overridesLibrary(ClassNode classNode, MethodNode method) {
        Set<String> visited = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        addParents(classNode, todo);
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!visited.add(name)) {
                continue;
            }
            ClassNode node = classes.get(name);
            if (node == null) {
                node = classpath.get(name);
            }
            if (node != null) {
                if (classes.get(name) == null) {
                    for (MethodNode other : node.methods) {
                        if (other.name.equals(method.name) && other.desc.equals(method.desc)) {
                            return true;
                        }
                    }
                }
                addParents(node, todo);
                continue;
            }
            Boolean found = reflective(name, method);
            if (found == null || found) {
                return true;
            }
            Class<?> type = load(name);
            if (type != null) {
                if (type.getSuperclass() != null) {
                    todo.add(Type.getInternalName(type.getSuperclass()));
                }
                for (Class<?> iface : type.getInterfaces()) {
                    todo.add(Type.getInternalName(iface));
                }
            }
        }
        return false;
    }

    private static void addParents(ClassNode node, Deque<String> todo) {
        if (node.superName != null) {
            todo.add(node.superName);
        }
        if (node.interfaces != null) {
            todo.addAll(node.interfaces);
        }
    }

    private static Class<?> load(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    private static Boolean reflective(String internalName, MethodNode method) {
        Class<?> type = load(internalName);
        if (type == null) {
            return null;
        }
        for (Method declared : type.getDeclaredMethods()) {
            if (declared.getName().equals(method.name) && Type.getMethodDescriptor(declared).equals(method.desc)) {
                return true;
            }
        }
        return false;
    }
}
