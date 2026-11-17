package dev.joyel.zbyte.reanalysis;

import org.objectweb.asm.Type;

public final class Types {
    public static final Type OBJECT_TYPE = Type.getObjectType("java/lang/Object");
    public static final Type CLASS_TYPE = Type.getObjectType("java/lang/Class");
    public static final Type STRING_TYPE = Type.getObjectType("java/lang/String");
    public static final Type CHARSET_TYPE = Type.getObjectType("java/nio/charset/Charset");
    public static final Type ARRAY_1D_BOOLEAN = Type.getObjectType("[Z");
    public static final Type ARRAY_1D_CHAR = Type.getObjectType("[C");
    public static final Type ARRAY_1D_BYTE = Type.getObjectType("[B");
    public static final Type ARRAY_1D_SHORT = Type.getObjectType("[S");
    public static final Type ARRAY_1D_INT = Type.getObjectType("[I");
    public static final Type ARRAY_1D_FLOAT = Type.getObjectType("[F");
    public static final Type ARRAY_1D_DOUBLE = Type.getObjectType("[D");
    public static final Type ARRAY_1D_LONG = Type.getObjectType("[J");
    public static final Type ARRAY_1D_OBJECT = Type.getObjectType("[Ljava/lang/Object;");
    public static final Type ARRAY_1D_STRING = Type.getObjectType("[Ljava/lang/String;");

    private Types() {
    }

    public static boolean isPrimitive(Type type) {
        return type != null && type.getSort() <= Type.DOUBLE;
    }

    public static boolean isPrimitive(String desc) {
        if (desc == null || desc.length() != 1)
            return false;
        return isPrimitive(desc.charAt(0));
    }

    public static boolean isPrimitive(char c) {
        return switch (c) {
            case 'V', 'Z', 'C', 'B', 'S', 'I', 'F', 'J', 'D' -> true;
            default -> false;
        };
    }

    public static Type undimension(Type arrayType) {
        if (arrayType.getSort() != Type.ARRAY)
            throw new IllegalStateException("Not an array: " + arrayType);
        return Type.getType(arrayType.getDescriptor().substring(1));
    }

    public static boolean isArraySuperType(String type) {
        return "java/lang/Object".equals(type)
                || "java/lang/Cloneable".equals(type)
                || "java/io/Serializable".equals(type);
    }

    public static Type fromSort(int sort) {
        return switch (sort) {
            case Type.VOID -> Type.VOID_TYPE;
            case Type.BOOLEAN -> Type.BOOLEAN_TYPE;
            case Type.CHAR -> Type.CHAR_TYPE;
            case Type.BYTE -> Type.BYTE_TYPE;
            case Type.SHORT -> Type.SHORT_TYPE;
            case Type.INT -> Type.INT_TYPE;
            case Type.FLOAT -> Type.FLOAT_TYPE;
            case Type.LONG -> Type.LONG_TYPE;
            case Type.DOUBLE -> Type.DOUBLE_TYPE;
            default -> OBJECT_TYPE;
        };
    }

    public static boolean isArrayStorable(Type arrayType, Type valueType, boolean recursive) {
        if (arrayType.getSort() != Type.ARRAY)
            return false;
        Type componentType = Type.getType(arrayType.getDescriptor().substring(1));
        if (isArrayStoreCompatible(componentType, valueType))
            return true;
        if (recursive && componentType.getSort() == Type.ARRAY)
            return isArrayStorable(componentType, valueType, true);
        return false;
    }

    public static boolean isArrayStoreCompatible(Type arrayComponent, Type value) {
        if (arrayComponent.equals(value))
            return true;
        int aSort = arrayComponent.getSort();
        int vSort = value.getSort();
        if (aSort >= Type.BOOLEAN && aSort <= Type.DOUBLE && vSort >= Type.BOOLEAN && vSort <= Type.DOUBLE) {
            if (aSort == Type.BOOLEAN || vSort == Type.BOOLEAN)
                return false;
            return switch (aSort) {
                case Type.DOUBLE -> true;
                case Type.FLOAT -> vSort != Type.DOUBLE;
                case Type.LONG -> vSort <= Type.LONG && vSort != Type.FLOAT;
                case Type.INT -> vSort == Type.BYTE || vSort == Type.SHORT || vSort == Type.CHAR || vSort == Type.INT;
                case Type.SHORT -> vSort == Type.BYTE || vSort == Type.SHORT;
                default -> false;
            };
        }
        if (aSort == Type.OBJECT || aSort == Type.ARRAY) {
            if (aSort == Type.OBJECT && "java/lang/Object".equals(arrayComponent.getInternalName()))
                return vSort == Type.OBJECT || vSort == Type.ARRAY;
            if (aSort == Type.ARRAY && vSort == Type.ARRAY) {
                Type tComp = Type.getType(arrayComponent.getDescriptor().substring(1));
                Type sComp = Type.getType(value.getDescriptor().substring(1));
                return isArrayStoreCompatible(tComp, sComp);
            }
        }
        return false;
    }

    public static int getNormalizedSort(int sort) {
        if (sort == Type.ARRAY)
            sort = Type.OBJECT;
        else if (sort > Type.VOID && sort < Type.INT)
            sort = Type.INT;
        return sort;
    }

    public static boolean isReference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }
}
