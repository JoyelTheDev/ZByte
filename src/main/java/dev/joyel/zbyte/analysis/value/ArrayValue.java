package dev.joyel.zbyte.analysis.value;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Types;
import dev.joyel.zbyte.analysis.Nullness;
import dev.joyel.zbyte.analysis.ReFrame;
import dev.joyel.zbyte.analysis.value.impl.ArrayValueImpl;

import java.util.Arrays;
import java.util.OptionalInt;

public interface ArrayValue extends ObjectValue {
	ArrayValue VAL_BOOLEANS = new ArrayValueImpl(Types.ARRAY_1D_BOOLEAN, Nullness.NOT_NULL);
	ArrayValue VAL_BOOLEANS_NULL = new ArrayValueImpl(Types.ARRAY_1D_BOOLEAN, Nullness.NULL);
	ArrayValue VAL_BOOLEANS_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_BOOLEAN, Nullness.UNKNOWN);
	ArrayValue VAL_CHARS = new ArrayValueImpl(Types.ARRAY_1D_CHAR, Nullness.NOT_NULL);
	ArrayValue VAL_CHARS_NULL = new ArrayValueImpl(Types.ARRAY_1D_CHAR, Nullness.NULL);
	ArrayValue VAL_CHARS_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_CHAR, Nullness.UNKNOWN);
	ArrayValue VAL_BYTES = new ArrayValueImpl(Types.ARRAY_1D_BYTE, Nullness.NOT_NULL);
	ArrayValue VAL_BYTES_NULL = new ArrayValueImpl(Types.ARRAY_1D_BYTE, Nullness.NULL);
	ArrayValue VAL_BYTES_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_BYTE, Nullness.UNKNOWN);
	ArrayValue VAL_SHORTS = new ArrayValueImpl(Types.ARRAY_1D_SHORT, Nullness.NOT_NULL);
	ArrayValue VAL_SHORTS_NULL = new ArrayValueImpl(Types.ARRAY_1D_SHORT, Nullness.NULL);
	ArrayValue VAL_SHORTS_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_SHORT, Nullness.UNKNOWN);
	ArrayValue VAL_INTS = new ArrayValueImpl(Types.ARRAY_1D_INT, Nullness.NOT_NULL);
	ArrayValue VAL_INTS_NULL = new ArrayValueImpl(Types.ARRAY_1D_INT, Nullness.NULL);
	ArrayValue VAL_INTS_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_INT, Nullness.UNKNOWN);
	ArrayValue VAL_FLOATS = new ArrayValueImpl(Types.ARRAY_1D_FLOAT, Nullness.NOT_NULL);
	ArrayValue VAL_FLOATS_NULL = new ArrayValueImpl(Types.ARRAY_1D_FLOAT, Nullness.NULL);
	ArrayValue VAL_FLOATS_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_FLOAT, Nullness.UNKNOWN);
	ArrayValue VAL_DOUBLES = new ArrayValueImpl(Types.ARRAY_1D_DOUBLE, Nullness.NOT_NULL);
	ArrayValue VAL_DOUBLES_NULL = new ArrayValueImpl(Types.ARRAY_1D_DOUBLE, Nullness.NULL);
	ArrayValue VAL_DOUBLES_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_DOUBLE, Nullness.UNKNOWN);
	ArrayValue VAL_LONGS = new ArrayValueImpl(Types.ARRAY_1D_LONG, Nullness.NOT_NULL);
	ArrayValue VAL_LONGS_NULL = new ArrayValueImpl(Types.ARRAY_1D_LONG, Nullness.NULL);
	ArrayValue VAL_LONGS_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_LONG, Nullness.UNKNOWN);
	ArrayValue VAL_OBJECTS = new ArrayValueImpl(Types.ARRAY_1D_OBJECT, Nullness.NOT_NULL);
	ArrayValue VAL_OBJECTS_NULL = new ArrayValueImpl(Types.ARRAY_1D_OBJECT, Nullness.NULL);
	ArrayValue VAL_OBJECTS_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_OBJECT, Nullness.UNKNOWN);
	ArrayValue VAL_STRINGS = new ArrayValueImpl(Types.ARRAY_1D_STRING, Nullness.NOT_NULL);
	ArrayValue VAL_STRINGS_NULL = new ArrayValueImpl(Types.ARRAY_1D_STRING, Nullness.NULL);
	ArrayValue VAL_STRINGS_MAYBE_NULL = new ArrayValueImpl(Types.ARRAY_1D_STRING, Nullness.UNKNOWN);

	static ArrayValue of(Type type, Nullness nullness) {
		String descriptor = type.getDescriptor();
		return switch (descriptor) {
			case "[Z" -> nullness == Nullness.NULL ? VAL_BOOLEANS_NULL : nullness == Nullness.UNKNOWN ? VAL_BOOLEANS_MAYBE_NULL : VAL_BOOLEANS;
			case "[C" -> nullness == Nullness.NULL ? VAL_CHARS_NULL : nullness == Nullness.UNKNOWN ? VAL_CHARS_MAYBE_NULL : VAL_CHARS;
			case "[B" -> nullness == Nullness.NULL ? VAL_BYTES_NULL : nullness == Nullness.UNKNOWN ? VAL_BYTES_MAYBE_NULL : VAL_BYTES;
			case "[S" -> nullness == Nullness.NULL ? VAL_SHORTS_NULL : nullness == Nullness.UNKNOWN ? VAL_SHORTS_MAYBE_NULL : VAL_SHORTS;
			case "[I" -> nullness == Nullness.NULL ? VAL_INTS_NULL : nullness == Nullness.UNKNOWN ? VAL_INTS_MAYBE_NULL : VAL_INTS;
			case "[F" -> nullness == Nullness.NULL ? VAL_FLOATS_NULL : nullness == Nullness.UNKNOWN ? VAL_FLOATS_MAYBE_NULL : VAL_FLOATS;
			case "[D" -> nullness == Nullness.NULL ? VAL_DOUBLES_NULL : nullness == Nullness.UNKNOWN ? VAL_DOUBLES_MAYBE_NULL : VAL_DOUBLES;
			case "[J" -> nullness == Nullness.NULL ? VAL_LONGS_NULL : nullness == Nullness.UNKNOWN ? VAL_LONGS_MAYBE_NULL : VAL_LONGS;
			case "[Ljava/lang/String;" -> nullness == Nullness.NULL ? VAL_STRINGS_NULL : nullness == Nullness.UNKNOWN ? VAL_STRINGS_MAYBE_NULL : VAL_STRINGS;
			case "[Ljava/lang/Object;" -> nullness == Nullness.NULL ? VAL_OBJECTS_NULL : nullness == Nullness.UNKNOWN ? VAL_OBJECTS_MAYBE_NULL : VAL_OBJECTS;
			default -> new ArrayValueImpl(type, nullness);
		};
	}

	static ArrayValue of(Type type, Nullness nullness, int length) {
		return new ArrayValueImpl(type, nullness, length);
	}

	static ReValue multiANewArray(Type type, int[] dimensions) {
		int length = dimensions[0];
		if (dimensions.length == 1)
			return of(type, Nullness.NOT_NULL, length);
		return new ArrayValueImpl(type, Nullness.NOT_NULL, length,
				i -> multiANewArray(Types.undimension(type), Arrays.copyOfRange(dimensions, 1, dimensions.length))
		);
	}

	default boolean canStore(ReValue value, boolean recursive) {
		Type valueType = value.type();
		if (valueType == null)
			return false;
		return Types.isArrayStorable(type(), valueType, recursive);
	}

	boolean containsTrackedSubArray();

	ArrayValue setValue(int index, ReValue value);

	ArrayValue updatedCopyIfContained(ReValue originalValue, ReValue updatedValue);

	@Override
	default boolean hasKnownValue() {
		return false;
	}

	@Override
	Type type();

	default Type elementType() {
		return type().getElementType();
	}

	default int dimensions() {
		return type().getDimensions();
	}

	OptionalInt getFirstDimensionLength();

	ReValue getValue(int index);
}
