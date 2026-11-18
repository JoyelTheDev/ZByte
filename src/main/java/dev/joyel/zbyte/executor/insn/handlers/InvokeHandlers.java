package dev.joyel.zbyte.executor.insn.handlers;

import static org.objectweb.asm.Opcodes.*;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.MethodExecutor;
import dev.joyel.zbyte.executor.defined.types.JavaClass;
import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.exceptions.NoSuchMethodHandlerException;
import dev.joyel.zbyte.executor.insn.ExecutionFrame;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.JavaArray;
import dev.joyel.zbyte.executor.values.JavaBoolean;
import dev.joyel.zbyte.executor.values.JavaByte;
import dev.joyel.zbyte.executor.values.JavaCharacter;
import dev.joyel.zbyte.executor.values.JavaDouble;
import dev.joyel.zbyte.executor.values.JavaFloat;
import dev.joyel.zbyte.executor.values.JavaInteger;
import dev.joyel.zbyte.executor.values.JavaLong;
import dev.joyel.zbyte.executor.values.JavaObject;
import dev.joyel.zbyte.executor.values.JavaShort;
import dev.joyel.zbyte.executor.values.JavaTop;
import dev.joyel.zbyte.executor.values.JavaValue;
import dev.joyel.zbyte.utils.TypeStore;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

public final class InvokeHandlers {
    private InvokeHandlers() {
    }

    public static void register(InsnDispatcher dispatcher) {
        dispatcher.set(InvokeHandlers::invokeVirtual, INVOKEVIRTUAL);
        dispatcher.set(InvokeHandlers::invokeSpecial, INVOKESPECIAL);
        dispatcher.set(InvokeHandlers::invokeStatic, INVOKESTATIC);
        dispatcher.set(InvokeHandlers::invokeInterface, INVOKEINTERFACE);
        dispatcher.set(InvokeHandlers::invokeDynamic, INVOKEDYNAMIC);
    }

    private static void invokeVirtual(ExecutionFrame f, AbstractInsnNode insn) {
        MethodInsnNode cast = (MethodInsnNode) insn;
        List<JavaValue> args = popArguments(f, cast, false);
        args.add(f.stack.remove(0));
        if (applyCustom(f, insn, args, Type.getReturnType(cast.desc))) {
            return;
        }
        String receiverType = args.get(args.size() - 1).type();
        invokeUpHierarchy(f, cast, args, receiverType, receiverType + " ");
    }

    private static void invokeSpecial(ExecutionFrame f, AbstractInsnNode insn) {
        MethodInsnNode cast = (MethodInsnNode) insn;
        List<JavaValue> args = popArguments(f, cast, false);
        args.add(f.stack.remove(0));
        if (applyCustom(f, insn, args, Type.getReturnType(cast.desc))) {
            return;
        }
        invokeUpHierarchy(f, cast, args, cast.owner, "");
    }

    private static void invokeStatic(ExecutionFrame f, AbstractInsnNode insn) {
        MethodInsnNode cast = (MethodInsnNode) insn;
        Type type = Type.getReturnType(cast.desc);
        List<JavaValue> args = popArguments(f, cast, true);
        if (applyCustom(f, insn, args, type)) {
            return;
        }
        Context context = f.context;
        if (context.provider.canInvokeMethod(cast.owner, cast.name, cast.desc, null, args, context)) {
            Object provided = context.provider.invokeMethod(cast.owner, cast.name, cast.desc, null, args, context);
            pushReturned(f.stack, type, provided);
        } else {
            throw new NoSuchMethodHandlerException("Could not find invoker for " + cast.owner + " " + cast.name + cast.desc).setThrownFromInvoke(true);
        }
    }

    private static void invokeInterface(ExecutionFrame f, AbstractInsnNode insn) {
        MethodInsnNode cast = (MethodInsnNode) insn;
        Type type = Type.getReturnType(cast.desc);
        List<JavaValue> args = popArguments(f, cast, false);
        args.add(f.stack.remove(0));
        if (applyCustom(f, insn, args, type)) {
            return;
        }
        Context context = f.context;
        JavaValue receiver = args.get(args.size() - 1);
        List<JavaValue> params = args.subList(0, args.size() - 1);
        if (context.provider.canInvokeMethod(receiver.type(), cast.name, cast.desc, receiver, params, context)) {
            Object provided = context.provider.invokeMethod(receiver.type(), cast.name, cast.desc, receiver, params, context);
            pushReturned(f.stack, type, provided);
        } else if (context.provider.canInvokeMethod(cast.owner, cast.name, cast.desc, receiver, params, context)) {
            Object provided = context.provider.invokeMethod(cast.owner, cast.name, cast.desc, receiver, params, context);
            pushReturned(f.stack, type, provided);
        } else {
            throw new NoSuchMethodHandlerException("Could not find invoker for " + receiver.type() + " " + cast.owner + " " + cast.name + cast.desc).setThrownFromInvoke(true);
        }
    }

    private static void invokeDynamic(ExecutionFrame f, AbstractInsnNode insn) {
        Context context = f.context;
        if (!context.customMethodFunc.containsKey(insn)) {
            throw new ExecutionException(new UnsupportedOperationException());
        }
        InvokeDynamicInsnNode cast = (InvokeDynamicInsnNode) insn;
        List<JavaValue> args = new ArrayList<>();
        args.add(new JavaObject(null, "java/lang/invoke/MethodHandles$Lookup"));
        args.add(JavaValue.valueOf(cast.name));
        args.add(new JavaObject(cast.desc, "java/lang/invoke/MethodType"));
        Type[] argumentTypes = Type.getArgumentTypes(cast.bsm.getDesc());
        for (int i = 0; i < cast.bsmArgs.length; i++) {
            args.add(bootstrapArgument(context, cast.bsmArgs[i], argumentTypes[i + 3]));
        }
        List<JavaValue> pulled = new ArrayList<>();
        List<Type> types = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(cast.desc)));
        Collections.reverse(types);
        for (Type t : types) {
            if (t.getSort() == Type.LONG || t.getSort() == Type.DOUBLE) {
                if (!(f.stack.get(0) instanceof JavaTop)) {
                    throw new ExecutionException("Expected JavaTop");
                }
                f.stack.remove(0);
            }
            pulled.add(0, f.stack.remove(0).copy());
        }
        args.addAll(pulled);
        applyCustom(f, insn, args, Type.getReturnType(cast.desc));
    }

    private static JavaValue bootstrapArgument(Context context, Object arg, Type declared) {
        if (arg.getClass() == Type.class) {
            Type type = (Type) arg;
            return JavaValue.valueOf(new JavaClass(type.getInternalName().replace('/', '.'), context));
        }
        switch (declared.getSort()) {
            case Type.BOOLEAN:
                return new JavaBoolean((Boolean) arg);
            case Type.CHAR:
                return new JavaCharacter((Character) arg);
            case Type.BYTE:
                return new JavaByte((Byte) arg);
            case Type.SHORT:
                return new JavaShort((Short) arg);
            case Type.INT:
                return new JavaInteger((Integer) arg);
            case Type.FLOAT:
                return new JavaFloat((Float) arg);
            case Type.LONG:
                return new JavaLong((Long) arg);
            case Type.DOUBLE:
                return new JavaDouble((Double) arg);
            default:
                return JavaValue.valueOf(arg);
        }
    }

    private static List<JavaValue> popArguments(ExecutionFrame f, MethodInsnNode cast, boolean detailed) {
        List<JavaValue> stack = f.stack;
        Type[] argumentTypes = Type.getArgumentTypes(cast.desc);
        List<JavaValue> args = new ArrayList<>();
        List<Type> reversed = new ArrayList<>(Arrays.asList(argumentTypes));
        Collections.reverse(reversed);
        for (Type t : reversed) {
            if (t.getSort() == Type.LONG || t.getSort() == Type.DOUBLE) {
                if (!(stack.get(0) instanceof JavaTop)) {
                    if (detailed) {
                        throw new ExecutionException("Expected JavaTop while invoking " + cast.owner + " " + cast.name + " " + cast.desc + ", but got " + stack.get(0).getClass().getSimpleName());
                    }
                    throw new ExecutionException("Expected JavaTop");
                }
                stack.remove(0);
            }
            args.add(0, stack.remove(0).copy());
        }
        MethodExecutor.convertArgs(args, argumentTypes);
        return args;
    }

    private static boolean applyCustom(ExecutionFrame f, AbstractInsnNode insn, List<JavaValue> args, Type returnType) {
        Context context = f.context;
        if (!context.customMethodFunc.containsKey(insn)) {
            return false;
        }
        BiFunction<List<JavaValue>, Context, JavaValue> custom = context.customMethodFunc.get(insn);
        JavaValue result = custom.apply(args, context);
        if (returnType.getSort() == Type.VOID) {
            return true;
        }
        f.stack.add(0, result);
        if (result instanceof JavaLong || result instanceof JavaDouble) {
            f.stack.add(0, new JavaTop());
        }
        return true;
    }

    private static void invokeUpHierarchy(ExecutionFrame f, MethodInsnNode cast, List<JavaValue> args, String startOwner, String receiverPrefix) {
        Context context = f.context;
        Type type = Type.getReturnType(cast.desc);
        JavaValue receiver = args.get(args.size() - 1);
        List<JavaValue> params = args.subList(0, args.size() - 1);
        String owner = startOwner;
        while (true) {
            try {
                if (context.provider.canInvokeMethod(owner, cast.name, cast.desc, receiver, params, context)) {
                    Object provided = context.provider.invokeMethod(owner, cast.name, cast.desc, receiver, params, context);
                    pushReturned(f.stack, type, provided);
                } else {
                    throw new NoSuchMethodHandlerException("Could not find invoker for " + receiverPrefix + cast.owner + " " + cast.name + cast.desc).setThrownFromInvoke(true);
                }
                return;
            } catch (NoSuchMethodHandlerException | IllegalArgumentException t) {
                if (t instanceof NoSuchMethodHandlerException
                        && !((NoSuchMethodHandlerException) t).isThrownFromInvoke()) {
                    throw t;
                }
                ClassNode ownerClass = context.dictionary.get(owner);
                if (ownerClass != null && ownerClass.superName != null) {
                    owner = ownerClass.superName;
                    continue;
                }
                throw t;
            }
        }
    }

    private static void pushReturned(List<JavaValue> stack, Type type, Object provided) {
        StackOps.push(stack, type.getSort(), provided, InvokeHandlers::pushReturnedReference);
    }

    private static void pushReturnedReference(List<JavaValue> stack, Object provided) {
        long thread = Thread.currentThread().getId();
        JavaObject returned = TypeStore.returnObjects.get(thread);
        boolean matches = provided != null && returned != null && returned.value() == provided;
        if (provided != null && provided.getClass().isArray()) {
            if (matches) {
                JavaArray array = (JavaArray) returned;
                stack.add(0, new JavaArray(array.value(), array.getTypeArray()));
                TypeStore.returnObjects.remove(thread);
            } else {
                stack.add(0, new JavaArray(provided));
            }
        } else if (matches) {
            stack.add(0, new JavaObject(provided, returned.type()));
            TypeStore.returnObjects.remove(thread);
        } else {
            stack.add(0, JavaValue.valueOf(provided));
        }
    }
}
