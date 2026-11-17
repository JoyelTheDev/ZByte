package dev.joyel.zbyte.reanalysis;

import dev.joyel.zbyte.executor.Context;
import dev.joyel.zbyte.executor.providers.ComparisonProvider;
import dev.joyel.zbyte.executor.values.JavaValue;
import org.objectweb.asm.Type;

public final class HostComparisonProvider extends ComparisonProvider {

    private static Class<?> load(Type type) {
        try {
            String name = type.getSort() == Type.ARRAY ? type.getDescriptor().replace('/', '.') : type.getClassName();
            return Class.forName(name, false, ClassLoader.getPlatformClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean isHostValue(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isArray();
    }

    @Override
    public boolean canCheckInstanceOf(JavaValue target, Type type, Context context) {
        Object value = target.value();
        return isHostValue(value) && (value == null || load(type) != null);
    }

    @Override
    public boolean instanceOf(JavaValue target, Type type, Context context) {
        Object value = target.value();
        if (value == null) {
            return false;
        }
        Class<?> host = load(type);
        return host != null && host.isInstance(value);
    }

    @Override
    public boolean canCheckcast(JavaValue target, Type type, Context context) {
        return canCheckInstanceOf(target, type, context);
    }

    @Override
    public boolean checkcast(JavaValue target, Type type, Context context) {
        Object value = target.value();
        if (value == null) {
            return true;
        }
        Class<?> host = load(type);
        return host != null && host.isInstance(value);
    }

    @Override
    public boolean canCheckEquality(JavaValue first, JavaValue second, Context context) {
        Object a = first.value();
        Object b = second.value();
        return (a == null || b == null) || (isHostValue(a) && isHostValue(b));
    }

    @Override
    public boolean checkEquality(JavaValue first, JavaValue second, Context context) {
        Object a = first.value();
        Object b = second.value();
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof String && b instanceof String) {
            return a.equals(b);
        }
        return a == b;
    }
}
