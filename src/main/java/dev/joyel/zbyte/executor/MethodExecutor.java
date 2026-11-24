package dev.joyel.zbyte.executor;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;

import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.exceptions.FallingOffCodeException;
import dev.joyel.zbyte.executor.exceptions.NoSuchMethodHandlerException;
import dev.joyel.zbyte.executor.insn.ExceptionRouter;
import dev.joyel.zbyte.executor.insn.ExecutionFrame;
import dev.joyel.zbyte.executor.insn.InsnDispatcher;
import dev.joyel.zbyte.executor.values.*;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import dev.joyel.zbyte.utils.Utils;

public class MethodExecutor {
    public static final boolean VERIFY;
    private static final boolean DEBUG;
    private static final boolean DEBUG_PRINT_EXCEPTIONS;
    private static final List<String> DEBUG_CLASSES;
    private static final List<String> DEBUG_METHODS_WITH_DESC;

    static {
        VERIFY = false;
        DEBUG = false;
        DEBUG_PRINT_EXCEPTIONS = false;
        DEBUG_CLASSES = Arrays.asList();
        DEBUG_METHODS_WITH_DESC = Arrays.asList();
    }

    public static <T> T execute(ClassNode classNode, MethodNode method, List<JavaValue> args, Object instance, Context context) {
        if (context == null)
            throw new IllegalArgumentException("Null context");
        List<JavaValue> stack = new LinkedList<>();
        List<JavaValue> locals = new LinkedList<>();
        if (!Modifier.isStatic(method.access)) {
            if (instance == null) {
                locals.add(new JavaObject(null, "java/lang/Object"));
            } else {
                locals.add((JavaValue) instance);
            }
        }
        if (args != null) {
            for (JavaValue arg : args) {
                locals.add(arg.copy());
                if (arg instanceof JavaDouble || arg instanceof JavaLong) {
                    locals.add(new JavaTop());
                }
            }
        }
        for (int i = 0; i < method.maxLocals + 50; i++) {
            locals.add(null);
        }
        return execute(classNode, method, method.instructions.getFirst(), stack, locals, context);
    }

    public static Object convert(Object value, String type) {
        if (value instanceof Number && Utils.canReturnDigit(type)) {
            Number cast = (Number) value;
            switch (type) {
                case "I":
                    return cast.intValue();
                case "S":
                    return cast.shortValue();
                case "B":
                    return cast.byteValue();
                case "J":
                    return cast.longValue();
                case "Z":
                    return cast.intValue() != 0;
                case "C":
                    return (char) cast.intValue();
            }
        }

        return value;
    }

    public static void convertArgs(List<JavaValue> args, Type[] methodParams) {
        for (int i = 0; i < args.size(); i++) {
            JavaValue arg = args.get(i);
            if (arg instanceof JavaInteger && methodParams[i].getDescriptor().equals("I"))
                args.set(i, new JavaInteger(arg.intValue()));
        }
    }

    public static Object value(JavaValue value) {
        Object val;

        switch (value.getClass().getSimpleName()) {
            case "JavaByte":
                val = ((JavaByte) value).byteValue();
                break;
            case "JavaCharacter":
                val = ((JavaCharacter) value).charValue();
                break;
            case "JavaShort":
                val = ((JavaShort) value).shortValue();
                break;
            case "JavaInteger":
                val = value.intValue();
                break;
            case "JavaBoolean":
                val = value.booleanValue();
                break;
            case "JavaLong":
                val = value.longValue();
                break;
            case "JavaFloat":
                val = value.floatValue();
                break;
            case "JavaDouble":
                val = value.doubleValue();
                break;
            case "JavaObject":
            case "JavaArray":
                val = value.value();
                break;
            default:
                throw new ExecutionException("Unknown value type " + value.getClass().getSimpleName());
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    private static <T> T execute(ClassNode classNode, MethodNode method, AbstractInsnNode now, List<JavaValue> stack, List<JavaValue> locals, Context context) {
        context.push(classNode.name, method.name, classNode.sourceFile, 0);
        if (DEBUG) {
            System.out.println("Executing " + classNode.name + " " + method.name + method.desc);
        }
        ExecutionFrame frame = new ExecutionFrame(classNode, method, now, stack, locals, context);
        InsnDispatcher dispatcher = context.dispatcher;
        while (true) {
            try {
                if (DEBUG && (DEBUG_CLASSES.isEmpty() || DEBUG_CLASSES.contains(classNode.name)) && (DEBUG_METHODS_WITH_DESC.isEmpty() || DEBUG_METHODS_WITH_DESC.contains(method.name + method.desc))) {
                    System.out.println("\t Stack: " + stack);
                    System.out.println("\t Locals: " + locals);
                    System.out.println();
                    System.out.println(method.instructions.indexOf(frame.now) + " " + Utils.prettyprint(frame.now));
                }
                if (frame.now == null) {
                    throw new FallingOffCodeException();
                }

                context.meter.tick();
                context.doBreakpoint(frame.now, true, stack, locals, null);

                frame.toThrow = null;
                dispatcher.dispatch(frame);

                if (frame.returned) {
                    return (T) frame.result;
                }

                context.doBreakpoint(frame.now, false, stack, locals, frame.toThrow);

                if (frame.toThrow != null) {
                    if (DEBUG_PRINT_EXCEPTIONS) {
                        frame.toThrow.printStackTrace(System.out);
                    }
                    if (ExceptionRouter.route(frame, frame.toThrow)) {
                        continue;
                    }
                    throw new ExecutionException(frame.toThrow);
                }
                frame.now = frame.now.getNext();
            } catch (ExecutionException e) {
                if (e.clazz.isEmpty()) {
                    e.clazz = classNode.name;
                    e.method = method.name + method.desc;
                }
                if (e instanceof NoSuchMethodHandlerException)
                    ((NoSuchMethodHandlerException) e).setThrownFromInvoke(false);
                throw e;
            } catch (Throwable t) {
                if (DEBUG_PRINT_EXCEPTIONS) {
                    t.printStackTrace(System.out);
                }
                if (ExceptionRouter.route(frame, t)) {
                    continue;
                }
                Utils.sneakyThrow(t);
                return null;
            }
        }
    }
}
