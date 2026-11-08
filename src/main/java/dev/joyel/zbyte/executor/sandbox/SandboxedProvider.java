package dev.joyel.zbyte.executor.sandbox;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.providers.Provider;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.Type;

import java.util.List;

public final class SandboxedProvider implements Provider {

    private final Provider delegate;

    private SandboxedProvider(Provider delegate) {
        this.delegate = delegate;
    }

    public static Provider wrap(Provider provider) {
        if (provider == null || provider instanceof SandboxedProvider || !SandboxPolicy.active().isEnabled()) {
            return provider;
        }
        return new SandboxedProvider(provider);
    }

    public Provider unwrap() {
        return delegate;
    }

    private void guard(String kind, String owner, String name) {
        SandboxPolicy policy = SandboxPolicy.active();
        if (!policy.isDenied(owner, name)) {
            return;
        }
        policy.record(kind, owner, name);
        if (!policy.isLogOnly()) {
            throw new SandboxViolationException("Sandbox blocked " + kind + " " + owner + "." + name);
        }
    }

    @Override
    public Object invokeMethod(String className, String methodName, String methodDesc, JavaValue targetObject, List<JavaValue> args, Context context) {
        guard("invoke", className, methodName);
        return delegate.invokeMethod(className, methodName, methodDesc, targetObject, args, context);
    }

    @Override
    public boolean canInvokeMethod(String className, String methodName, String methodDesc, JavaValue targetObject, List<JavaValue> args, Context context) {
        if (SandboxPolicy.active().blocks(className, methodName)) {
            return true;
        }
        return delegate.canInvokeMethod(className, methodName, methodDesc, targetObject, args, context);
    }

    @Override
    public void setField(String className, String fieldName, String fieldDesc, JavaValue targetObject, Object value, Context context) {
        guard("setField", className, fieldName);
        delegate.setField(className, fieldName, fieldDesc, targetObject, value, context);
    }

    @Override
    public boolean canSetField(String className, String fieldName, String fieldDesc, JavaValue targetObject, Object value, Context context) {
        if (SandboxPolicy.active().blocks(className, fieldName)) {
            return true;
        }
        return delegate.canSetField(className, fieldName, fieldDesc, targetObject, value, context);
    }

    @Override
    public Object getField(String className, String fieldName, String fieldDesc, JavaValue targetObject, Context context) {
        guard("getField", className, fieldName);
        return delegate.getField(className, fieldName, fieldDesc, targetObject, context);
    }

    @Override
    public boolean canGetField(String className, String fieldName, String fieldDesc, JavaValue targetObject, Context context) {
        if (SandboxPolicy.active().blocks(className, fieldName)) {
            return true;
        }
        return delegate.canGetField(className, fieldName, fieldDesc, targetObject, context);
    }

    @Override
    public boolean instanceOf(JavaValue target, Type type, Context context) {
        return delegate.instanceOf(target, type, context);
    }

    @Override
    public boolean canCheckInstanceOf(JavaValue target, Type type, Context context) {
        return delegate.canCheckInstanceOf(target, type, context);
    }

    @Override
    public boolean checkcast(JavaValue target, Type type, Context context) {
        return delegate.checkcast(target, type, context);
    }

    @Override
    public boolean canCheckcast(JavaValue target, Type type, Context context) {
        return delegate.canCheckcast(target, type, context);
    }

    @Override
    public boolean checkEquality(JavaValue first, JavaValue second, Context context) {
        return delegate.checkEquality(first, second, context);
    }

    @Override
    public boolean canCheckEquality(JavaValue first, JavaValue second, Context context) {
        return delegate.canCheckEquality(first, second, context);
    }
}
