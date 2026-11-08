package dev.joyel.zbyte.analysis.value;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Types;
import dev.joyel.zbyte.analysis.Nullness;
import dev.joyel.zbyte.analysis.value.impl.ObjectValueImpl;
import dev.joyel.zbyte.analysis.value.impl.StringValueImpl;

public non-sealed interface ObjectValue extends ReValue {
	ObjectValue VAL_OBJECT = new ObjectValueImpl(Types.OBJECT_TYPE, Nullness.NOT_NULL);
	ObjectValue VAL_OBJECT_NULL = new ObjectValueImpl(Types.OBJECT_TYPE, Nullness.NULL);
	ObjectValue VAL_OBJECT_MAYBE_NULL = new ObjectValueImpl(Types.OBJECT_TYPE, Nullness.UNKNOWN);
	ObjectValue VAL_CLASS = new ObjectValueImpl(Types.CLASS_TYPE, Nullness.NOT_NULL);
	ObjectValue VAL_CLASS_NULL = new ObjectValueImpl(Types.CLASS_TYPE, Nullness.NULL);
	ObjectValue VAL_CLASS_MAYBE_NULL = new ObjectValueImpl(Types.CLASS_TYPE, Nullness.UNKNOWN);
	ObjectValue VAL_METHOD_TYPE = new ObjectValueImpl(Type.getObjectType("java/lang/invoke/MethodType"), Nullness.NOT_NULL);
	ObjectValue VAL_METHOD_HANDLE = new ObjectValueImpl(Type.getObjectType("java/lang/invoke/MethodType"), Nullness.NOT_NULL);
	ObjectValue VAL_JSR = new ObjectValueImpl(Type.VOID_TYPE, Nullness.NOT_NULL);

	static StringValue string(String text) {
		if (text == null) return StringValue.VAL_STRING_NULL;
		if (text.isEmpty()) return StringValue.VAL_STRING_EMPTY;
		if (text.equals(" ")) return StringValue.VAL_STRING_SPACE;
		return new StringValueImpl(text);
	}

	static ObjectValue clazz(Nullness nullness) {
		return switch (nullness) {
			case NULL -> VAL_CLASS_NULL;
			case NOT_NULL -> VAL_CLASS;
			case UNKNOWN -> VAL_CLASS_MAYBE_NULL;
		};
	}

	static StringValue string(Nullness nullness) {
		return switch (nullness) {
			case NULL -> StringValue.VAL_STRING_NULL;
			case NOT_NULL -> StringValue.VAL_STRING;
			case UNKNOWN -> StringValue.VAL_STRING_MAYBE_NULL;
		};
	}

	static ObjectValue object(Nullness nullness) {
		return switch (nullness) {
			case NULL -> VAL_OBJECT_NULL;
			case NOT_NULL -> VAL_OBJECT;
			case UNKNOWN -> VAL_OBJECT_MAYBE_NULL;
		};
	}

	static ObjectValue object(Type type, Nullness nullness) {
		if (Types.OBJECT_TYPE.equals(type))
			return object(nullness);
		if (Types.STRING_TYPE.equals(type))
			return string(nullness);
		if (Types.CLASS_TYPE.equals(type))
			return clazz(nullness);
		return new ObjectValueImpl(type, nullness);
	}

	@Override
	Type type();

	Nullness nullness();

	default boolean isNull() {
		return nullness() == Nullness.NULL;
	}

	default boolean isNotNull() {
		return nullness() == Nullness.NOT_NULL;
	}

	@Override
	default int getSize() {
		return 1;
	}
}
