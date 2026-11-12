package dev.joyel.zbyte.analysis.lookup;

import dev.joyel.zbyte.analysis.value.DoubleValue;
import dev.joyel.zbyte.analysis.value.FloatValue;
import dev.joyel.zbyte.analysis.value.IntValue;
import dev.joyel.zbyte.analysis.value.LongValue;
import dev.joyel.zbyte.analysis.value.ObjectValue;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.analysis.value.StringValue;
import dev.joyel.zbyte.analysis.value.impl.BoxedBooleanValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedByteValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedCharacterValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedDoubleValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedFloatValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedIntegerValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedLongValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedShortValueImpl;
import dev.joyel.zbyte.analysis.value.impl.ObjectValueBoxImpl;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BasicLookups {

    private static final Set<String> BOXES = Set.of(
            "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
            "java/lang/Character", "java/lang/Boolean", "java/lang/Float", "java/lang/Double");

    private static final Map<String, Set<String>> STATIC_METHODS = Map.of(
            "java/lang/Integer", Set.of("valueOf", "parseInt", "toString", "toHexString", "toBinaryString", "toOctalString", "bitCount",
                    "reverse", "reverseBytes", "rotateLeft", "rotateRight", "signum", "compare", "max", "min", "sum", "highestOneBit",
                    "lowestOneBit", "numberOfLeadingZeros", "numberOfTrailingZeros", "hashCode", "toUnsignedLong", "divideUnsigned", "remainderUnsigned"),
            "java/lang/Long", Set.of("valueOf", "parseLong", "toString", "toHexString", "toBinaryString", "toOctalString", "bitCount",
                    "reverse", "reverseBytes", "rotateLeft", "rotateRight", "signum", "compare", "max", "min", "sum", "highestOneBit",
                    "lowestOneBit", "numberOfLeadingZeros", "numberOfTrailingZeros", "hashCode", "divideUnsigned", "remainderUnsigned"),
            "java/lang/Short", Set.of("valueOf", "toString", "toUnsignedInt", "reverseBytes", "compare", "hashCode"),
            "java/lang/Byte", Set.of("valueOf", "toString", "toUnsignedInt", "compare", "hashCode"),
            "java/lang/Character", Set.of("valueOf", "toString", "isDigit", "isLetter", "isLetterOrDigit", "isWhitespace", "isUpperCase",
                    "isLowerCase", "toUpperCase", "toLowerCase", "getNumericValue", "digit", "forDigit", "compare", "hashCode"),
            "java/lang/Boolean", Set.of("valueOf", "parseBoolean", "toString", "compare", "hashCode", "logicalAnd", "logicalOr", "logicalXor"),
            "java/lang/Float", Set.of("valueOf", "parseFloat", "toString", "floatToIntBits", "floatToRawIntBits", "intBitsToFloat", "compare",
                    "isNaN", "isInfinite", "max", "min", "sum", "hashCode"),
            "java/lang/Double", Set.of("valueOf", "parseDouble", "toString", "doubleToLongBits", "doubleToRawLongBits", "longBitsToDouble",
                    "compare", "isNaN", "isInfinite", "max", "min", "sum", "hashCode"),
            "java/lang/Math", Set.of("abs", "max", "min", "floorDiv", "floorMod", "pow", "sqrt", "ceil", "floor", "round", "signum",
                    "negateExact", "multiplyHigh", "toIntExact", "cbrt", "hypot", "sin", "cos", "tan", "log", "log10", "exp")
    );

    private static final Set<String> STRING_STATIC = Set.of("valueOf");

    private static final Set<String> STRING_VIRTUAL = Set.of("length", "charAt", "isEmpty", "equals", "hashCode", "substring", "concat",
            "toString", "indexOf", "lastIndexOf", "contains", "startsWith", "endsWith", "trim", "intern", "compareTo", "equalsIgnoreCase",
            "codePointAt", "strip", "isBlank");

    private static final Set<String> BOX_VIRTUAL = Set.of("intValue", "longValue", "shortValue", "byteValue", "floatValue", "doubleValue",
            "charValue", "booleanValue", "toString", "equals", "hashCode", "compareTo");

    private BasicLookups() {
    }

    public static final class GetStatic implements GetStaticLookup {

        @Override
        public boolean hasLookup(FieldInsnNode field) {
            return constant(field) != null;
        }

        @Override
        public ReValue get(FieldInsnNode field) {
            return constant(field);
        }

        private static ReValue constant(FieldInsnNode field) {
            switch (field.owner + "." + field.name) {
                case "java/lang/Integer.MAX_VALUE":
                    return IntValue.of(Integer.MAX_VALUE);
                case "java/lang/Integer.MIN_VALUE":
                    return IntValue.of(Integer.MIN_VALUE);
                case "java/lang/Short.MAX_VALUE":
                    return IntValue.of(Short.MAX_VALUE);
                case "java/lang/Short.MIN_VALUE":
                    return IntValue.of(Short.MIN_VALUE);
                case "java/lang/Byte.MAX_VALUE":
                    return IntValue.of(Byte.MAX_VALUE);
                case "java/lang/Byte.MIN_VALUE":
                    return IntValue.of(Byte.MIN_VALUE);
                case "java/lang/Character.MAX_VALUE":
                    return IntValue.of(Character.MAX_VALUE);
                case "java/lang/Character.MIN_VALUE":
                    return IntValue.of(Character.MIN_VALUE);
                case "java/lang/Long.MAX_VALUE":
                    return LongValue.of(Long.MAX_VALUE);
                case "java/lang/Long.MIN_VALUE":
                    return LongValue.of(Long.MIN_VALUE);
                case "java/lang/Math.PI":
                    return DoubleValue.of(Math.PI);
                case "java/lang/Math.E":
                    return DoubleValue.of(Math.E);
                case "java/lang/Boolean.TRUE":
                    return new BoxedBooleanValueImpl(Boolean.TRUE);
                case "java/lang/Boolean.FALSE":
                    return new BoxedBooleanValueImpl(Boolean.FALSE);
                default:
                    return null;
            }
        }
    }

    public static final class InvokeStatic implements InvokeStaticLookup {

        private final Map<String, Method> cache = new HashMap<>();

        @Override
        public boolean hasLookup(MethodInsnNode method) {
            if (method.getOpcode() != org.objectweb.asm.Opcodes.INVOKESTATIC) {
                return false;
            }
            if (method.owner.equals("java/lang/String")) {
                return STRING_STATIC.contains(method.name) && allSimple(method.desc);
            }
            Set<String> names = STATIC_METHODS.get(method.owner);
            return names != null && names.contains(method.name) && allSimple(method.desc);
        }

        @Override
        public ReValue get(MethodInsnNode method, List<? extends ReValue> values) {
            Method target = resolve(cache, method);
            if (target == null) {
                return null;
            }
            Type[] parameters = Type.getArgumentTypes(method.desc);
            if (parameters.length != values.size()) {
                return null;
            }
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = toHost(values.get(i), parameters[i]);
                if (arguments[i] == null) {
                    return null;
                }
            }
            try {
                return fromHost(target.invoke(null, arguments), Type.getReturnType(method.desc));
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static final class InvokeVirtual implements InvokeVirtualLookup {

        private final Map<String, Method> cache = new HashMap<>();

        @Override
        public boolean hasLookup(MethodInsnNode method) {
            if (method.getOpcode() != org.objectweb.asm.Opcodes.INVOKEVIRTUAL) {
                return false;
            }
            if (method.owner.equals("java/lang/String")) {
                return STRING_VIRTUAL.contains(method.name) && allSimple(method.desc);
            }
            return BOXES.contains(method.owner) && BOX_VIRTUAL.contains(method.name) && allSimple(method.desc);
        }

        @Override
        public ReValue get(MethodInsnNode method, ReValue context, List<? extends ReValue> values) {
            Method target = resolve(cache, method);
            if (target == null) {
                return null;
            }
            Object receiver = toHost(context, Type.getObjectType(method.owner));
            Type[] parameters = Type.getArgumentTypes(method.desc);
            if (receiver == null || parameters.length != values.size()) {
                return null;
            }
            Object[] arguments = new Object[parameters.length];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = toHost(values.get(i), parameters[i]);
                if (arguments[i] == null) {
                    return null;
                }
            }
            try {
                return fromHost(target.invoke(receiver, arguments), Type.getReturnType(method.desc));
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static boolean allSimple(String descriptor) {
        for (Type type : Type.getArgumentTypes(descriptor)) {
            if (type.getSort() == Type.ARRAY || (type.getSort() == Type.OBJECT && !isSimpleObject(type))) {
                return false;
            }
        }
        Type returnType = Type.getReturnType(descriptor);
        return returnType.getSort() != Type.ARRAY && returnType.getSort() != Type.VOID
                && (returnType.getSort() != Type.OBJECT || isSimpleObject(returnType));
    }

    private static boolean isSimpleObject(Type type) {
        String name = type.getInternalName();
        return name.equals("java/lang/String") || name.equals("java/lang/Object") || BOXES.contains(name);
    }

    private static Method resolve(Map<String, Method> cache, MethodInsnNode method) {
        String key = method.owner + "." + method.name + method.desc;
        synchronized (cache) {
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            Method found = null;
            try {
                Class<?> owner = Class.forName(method.owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                Type[] types = Type.getArgumentTypes(method.desc);
                Class<?>[] classes = new Class<?>[types.length];
                for (int i = 0; i < types.length; i++) {
                    classes[i] = hostClass(types[i]);
                }
                found = owner.getMethod(method.name, classes);
            } catch (Throwable ignored) {
                found = null;
            }
            cache.put(key, found);
            return found;
        }
    }

    private static Class<?> hostClass(Type type) throws ClassNotFoundException {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return boolean.class;
            case Type.BYTE:
                return byte.class;
            case Type.CHAR:
                return char.class;
            case Type.SHORT:
                return short.class;
            case Type.INT:
                return int.class;
            case Type.LONG:
                return long.class;
            case Type.FLOAT:
                return float.class;
            case Type.DOUBLE:
                return double.class;
            default:
                return Class.forName(type.getClassName(), false, ClassLoader.getPlatformClassLoader());
        }
    }

    private static Object toHost(ReValue value, Type type) {
        if (value == null || !value.hasKnownValue()) {
            return null;
        }
        if (value instanceof IntValue v) {
            int i = v.value().getAsInt();
            switch (type.getSort()) {
                case Type.BOOLEAN:
                    return i != 0;
                case Type.BYTE:
                    return (byte) i;
                case Type.CHAR:
                    return (char) i;
                case Type.SHORT:
                    return (short) i;
                default:
                    return i;
            }
        }
        if (value instanceof LongValue v) {
            return v.value().getAsLong();
        }
        if (value instanceof FloatValue v) {
            return (float) v.value().getAsDouble();
        }
        if (value instanceof DoubleValue v) {
            return v.value().getAsDouble();
        }
        if (value instanceof StringValue v) {
            return v.getText().orElse(null);
        }
        if (value instanceof ObjectValueBoxImpl<?> box) {
            return box.value().orElse(null);
        }
        return null;
    }

    private static ReValue fromHost(Object result, Type returnType) {
        if (result == null) {
            return null;
        }
        switch (returnType.getSort()) {
            case Type.BOOLEAN:
                return IntValue.of(((Boolean) result) ? 1 : 0);
            case Type.CHAR:
                return IntValue.of((Character) result);
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return IntValue.of(((Number) result).intValue());
            case Type.LONG:
                return LongValue.of((Long) result);
            case Type.FLOAT:
                return FloatValue.of((Float) result);
            case Type.DOUBLE:
                return DoubleValue.of((Double) result);
            default:
                break;
        }
        if (result instanceof String s) {
            return ObjectValue.string(s);
        }
        if (result instanceof Integer i) {
            return new BoxedIntegerValueImpl(i);
        }
        if (result instanceof Long l) {
            return new BoxedLongValueImpl(l);
        }
        if (result instanceof Short s) {
            return new BoxedShortValueImpl(s);
        }
        if (result instanceof Byte b) {
            return new BoxedByteValueImpl(b);
        }
        if (result instanceof Character c) {
            return new BoxedCharacterValueImpl(c);
        }
        if (result instanceof Boolean b) {
            return new BoxedBooleanValueImpl(b);
        }
        if (result instanceof Float f) {
            return new BoxedFloatValueImpl(f);
        }
        if (result instanceof Double d) {
            return new BoxedDoubleValueImpl(d);
        }
        return null;
    }
}
