package dev.joyel.zbyte.executor.ssvm;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.exceptions.ExecutionException;
import dev.joyel.zbyte.executor.providers.MethodProvider;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public final class SsvmMethodProvider extends MethodProvider {

    private static final Logger logger = LoggerFactory.getLogger(SsvmMethodProvider.class);

    private final Map<String, ClassNode> classes;
    private SsvmBridge bridge;
    private boolean unavailable;

    public SsvmMethodProvider(Map<String, ClassNode> classes) {
        this.classes = classes;
    }

    @Override
    public boolean canInvokeMethod(String className, String methodName, String methodDesc, JavaValue targetObject, List<JavaValue> args, Context context) {
        if (unavailable || className == null || classes.containsKey(className)) {
            return false;
        }
        if (targetObject != null && !(targetObject.value() instanceof String)) {
            return false;
        }
        Type[] params = Type.getArgumentTypes(methodDesc);
        if (params.length != args.size() || !returnSupported(Type.getReturnType(methodDesc))) {
            return false;
        }
        for (int i = 0; i < params.length; i++) {
            if (!argumentSupported(params[i], args.get(i).value())) {
                return false;
            }
        }
        SsvmBridge active = bridge();
        return active != null && active.supports(className, methodName, methodDesc, targetObject == null);
    }

    @Override
    public Object invokeMethod(String className, String methodName, String methodDesc, JavaValue targetObject, List<JavaValue> args, Context context) {
        SsvmBridge active = bridge();
        if (active == null) {
            throw new ExecutionException("SSVM unavailable for " + className + "." + methodName + methodDesc);
        }
        Object[] raw = new Object[args.size()];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = args.get(i).value();
        }
        Object receiver = targetObject == null ? null : targetObject.value();
        try {
            return active.invoke(className, methodName, methodDesc, receiver, raw);
        } catch (RuntimeException | LinkageError e) {
            throw new ExecutionException(e);
        }
    }

    private synchronized SsvmBridge bridge() {
        if (bridge == null && !unavailable) {
            try {
                bridge = new SsvmBridge();
            } catch (Throwable t) {
                unavailable = true;
                logger.warn("SSVM failed to bootstrap, falling back to the built-in providers: {}", t.toString());
            }
        }
        return bridge;
    }

    private static boolean returnSupported(Type type) {
        return type.getSort() == Type.VOID
                || type.getSort() <= Type.DOUBLE
                || type.getDescriptor().equals("Ljava/lang/String;");
    }

    private static boolean argumentSupported(Type type, Object value) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return value instanceof Boolean || value instanceof Number;
            case Type.CHAR:
                return value instanceof Character || value instanceof Number;
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
            case Type.LONG:
            case Type.FLOAT:
            case Type.DOUBLE:
                return value instanceof Number;
            default:
                return type.getDescriptor().equals("Ljava/lang/String;") && (value == null || value instanceof String);
        }
    }
}
