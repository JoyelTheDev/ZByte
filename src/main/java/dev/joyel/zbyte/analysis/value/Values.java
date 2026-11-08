package dev.joyel.zbyte.analysis.value;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Types;
import dev.joyel.zbyte.analysis.Nullness;
import dev.joyel.zbyte.analysis.value.impl.ArrayValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedByteValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedCharacterValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedDoubleValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedFloatValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedIntegerValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedLongValueImpl;
import dev.joyel.zbyte.analysis.value.impl.BoxedShortValueImpl;
import dev.joyel.zbyte.analysis.value.impl.ObjectValueBoxImpl;

import java.util.OptionalInt;

import static dev.joyel.zbyte.analysis.Types.*;

public final class Values {
	private Values() {}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public static byte asByte(IntValue value) {
		return (byte) value.value().getAsInt();
	}

	public static boolean asBoolean(IntValue value) {
		return value.isNotEqualTo(0);
	}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public static short asShort(IntValue value) {
		return (short) value.value().getAsInt();
	}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public static char asChar(IntValue value) {
		return (char) value.value().getAsInt();
	}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public static int asInt(IntValue value) {
		return value.value().getAsInt();
	}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public static long asLong(LongValue value) {
		return value.value().getAsLong();
	}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public static float asFloat(FloatValue value) {
		return (float) value.value().getAsDouble();
	}

	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public static double asDouble(DoubleValue value) {
		return value.value().getAsDouble();
	}

	public static String asString(ObjectValue value) {
		if (value.isNull())
			return null;
		if (value instanceof StringValue string)
			return string.getText().orElseThrow(() -> new IllegalArgumentException("Unknown string value"));
		throw new IllegalArgumentException("Unsupported string unwrap: " + value);
	}

	public static String asStringOrNull(ReValue value) {
		if (!value.hasKnownValue())
			return null;
		try {
			if (value instanceof ObjectValue obj)
				return asString(obj);
		} catch (Exception e) {

		}
		return null;
	}

	public static Object asObjectLiteral(ObjectValue value) {
		return asObject(value);
	}

	@SuppressWarnings("unchecked")
	public static <T> T asObject(ObjectValue value) {
		if (value.isNull())
			return null;
		return switch (value) {
			case StringValue string -> (T) asString(string);
			case ObjectValueBoxImpl<?> box -> (T) box.unbox();
			default -> throw new IllegalArgumentException("Unsupported object unwrap: " + value);
		};
	}

	public static IntValue toBooleanValue(boolean value) {
		return IntValue.of(value ? 1 : 0);
	}

	public static IntValue toByteValue(byte value) {
		return IntValue.of(value);
	}

	public static IntValue toCharValue(char value) {
		return IntValue.of(value);
	}

	public static IntValue toShortValue(short value) {
		return IntValue.of(value);
	}

	public static IntValue toIntValue(int value) {
		return IntValue.of(value);
	}

	public static LongValue toLongValue(long value) {
		return LongValue.of(value);
	}

	public static FloatValue toFloatValue(float value) {
		return FloatValue.of(value);
	}

	public static DoubleValue toDoubleValue(double value) {
		return DoubleValue.of(value);
	}

	public static StringValue toStringValue(CharSequence value) {
		return ObjectValue.string(value == null ? null : value.toString());
	}

	public static ObjectValue toObjectValue(Object value) {
		return switch (value) {
			case null -> ObjectValue.VAL_OBJECT_NULL;
			case CharSequence sequence -> toStringValue(sequence);
			case Character character -> new BoxedCharacterValueImpl(character);
			case Integer number -> new BoxedIntegerValueImpl(number);
			case Long number -> new BoxedLongValueImpl(number);
			case Byte number -> new BoxedByteValueImpl(number);
			case Float number -> new BoxedFloatValueImpl(number);
			case Double number -> new BoxedDoubleValueImpl(number);
			case Short number -> new BoxedShortValueImpl(number);
			default -> throw new IllegalArgumentException("Unsupported Object wrap: " + value);
		};
	}

	public static ArrayValue asKnownArrayOfType(ReValue value, Type type) {
		if (value instanceof ArrayValue array
				&& type.equals(array.type())
				&& array.isNotNull()
				&& array.getFirstDimensionLength().isPresent())
			return array;
		return null;
	}

	private static ArrayValue getFullyKnownArray(ReValue value, Type type) {
		ArrayValue array = asKnownArrayOfType(value, type);
		return array != null && array.hasKnownValue() ? array : null;
	}

	private static <T> T getKnownHostArray(ReValue value, Type type, Class<T> arrayType) {
		return null;
	}

	public static boolean[] getKnownBooleanArray(ReValue value) {
		boolean[] hostArray = getKnownHostArray(value, ARRAY_1D_BOOLEAN, boolean[].class);
		if (hostArray != null)
			return hostArray;
		ArrayValue array = getFullyKnownArray(value, ARRAY_1D_BOOLEAN);
		return array == null ? null : asBooleanArray(array);
	}

	public static byte[] getKnownByteArray(ReValue value) {
		byte[] hostArray = getKnownHostArray(value, ARRAY_1D_BYTE, byte[].class);
		if (hostArray != null)
			return hostArray;
		ArrayValue array = getFullyKnownArray(value, ARRAY_1D_BYTE);
		return array == null ? null : asByteArray(array);
	}

	public static char[] getKnownCharArray(ReValue value) {
		char[] hostArray = getKnownHostArray(value, ARRAY_1D_CHAR, char[].class);
		if (hostArray != null)
			return hostArray;
		ArrayValue array = getFullyKnownArray(value, ARRAY_1D_CHAR);
		return array == null ? null : asCharArray(array);
	}

	public static int[] getKnownIntArray(ReValue value) {
		int[] hostArray = getKnownHostArray(value, ARRAY_1D_INT, int[].class);
		if (hostArray != null)
			return hostArray;
		ArrayValue array = getFullyKnownArray(value, ARRAY_1D_INT);
		return array == null ? null : asIntArray(array);
	}

	public static float[] getKnownFloatArray(ReValue value) {
		float[] hostArray = getKnownHostArray(value, ARRAY_1D_FLOAT, float[].class);
		if (hostArray != null)
			return hostArray;
		ArrayValue array = getFullyKnownArray(value, ARRAY_1D_FLOAT);
		return array == null ? null : asFloatArray(array);
	}

	public static double[] getKnownDoubleArray(ReValue value) {
		double[] hostArray = getKnownHostArray(value, ARRAY_1D_DOUBLE, double[].class);
		if (hostArray != null)
			return hostArray;
		ArrayValue array = getFullyKnownArray(value, ARRAY_1D_DOUBLE);
		return array == null ? null : asDoubleArray(array);
	}

	public static long[] getKnownLongArray(ReValue value) {
		long[] hostArray = getKnownHostArray(value, ARRAY_1D_LONG, long[].class);
		if (hostArray != null)
			return hostArray;
		ArrayValue array = getFullyKnownArray(value, ARRAY_1D_LONG);
		return array == null ? null : asLongArray(array);
	}

	public static boolean[] asBooleanArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asBooleanArray(array);
		return hostArray(value, boolean[].class);
	}

	public static byte[] asByteArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asByteArray(array);
		return hostArray(value, byte[].class);
	}

	public static short[] asShortArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asShortArray(array);
		return hostArray(value, short[].class);
	}

	public static char[] asCharArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asCharArray(array);
		return hostArray(value, char[].class);
	}

	public static int[] asIntArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asIntArray(array);
		return hostArray(value, int[].class);
	}

	public static float[] asFloatArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asFloatArray(array);
		return hostArray(value, float[].class);
	}

	public static double[] asDoubleArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asDoubleArray(array);
		return hostArray(value, double[].class);
	}

	public static long[] asLongArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asLongArray(array);
		return hostArray(value, long[].class);
	}

	public static String[] asStringArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asStringArray(array);

		Object hostValue = hostArray(value, Object[].class);
		if (hostValue instanceof String[] strings)
			return strings;

		Object[] objects = (Object[]) hostValue;
		String[] strings = new String[objects.length];
		for (int i = 0; i < objects.length; i++)
			strings[i] = objects[i] == null ? null : objects[i].toString();

		return strings;
	}

	public static Object[] asObjectArray(ReValue value) {
		if (value instanceof ArrayValue array)
			return asObjectArray(array);
		return hostArray(value, Object[].class);
	}

	public static boolean[] asBooleanArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		boolean[] booleans = new boolean[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof IntValue intValue && intValue.hasKnownValue())
				booleans[i] = asBoolean(intValue);
		}
		return booleans;
	}

	public static byte[] asByteArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof IntValue intValue && intValue.hasKnownValue())
				bytes[i] = asByte(intValue);
		}
		return bytes;
	}

	public static short[] asShortArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		short[] shorts = new short[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof IntValue intValue && intValue.hasKnownValue())
				shorts[i] = asShort(intValue);
		}
		return shorts;
	}

	public static char[] asCharArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		char[] chars = new char[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof IntValue intValue && intValue.hasKnownValue())
				chars[i] = asChar(intValue);
		}
		return chars;
	}

	public static int[] asIntArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		int[] ints = new int[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof IntValue intValue && intValue.hasKnownValue())
				ints[i] = asInt(intValue);
		}
		return ints;
	}

	public static float[] asFloatArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		float[] floats = new float[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof FloatValue floatValue && floatValue.hasKnownValue())
				floats[i] = asFloat(floatValue);
		}
		return floats;
	}

	public static double[] asDoubleArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		double[] doubles = new double[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof DoubleValue doubleValue && doubleValue.hasKnownValue())
				doubles[i] = asDouble(doubleValue);
		}
		return doubles;
	}

	public static long[] asLongArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		long[] longs = new long[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof LongValue longValue && longValue.hasKnownValue())
				longs[i] = asLong(longValue);
		}
		return longs;
	}

	public static String[] asStringArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		String[] strings = new String[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof ObjectValue objectValue && objectValue.hasKnownValue())
				strings[i] = asStringOrNull(objectValue);
		}
		return strings;
	}

	public static Object[] asObjectArray(ArrayValue value) {
		if (value.isNull())
			return null;
		int length = getKnownArrayLength(value);
		Object[] objects = new Object[length];
		for (int i = 0; i < length; i++) {
			ReValue element = value.getValue(i);
			if (element instanceof ObjectValue objectValue && objectValue.hasKnownValue())
				objects[i] = asObject(objectValue);
		}
		return objects;
	}

	public static ArrayValue toBooleanArrayValue(boolean[] value) {
		if (value == null)
			return ArrayValue.VAL_BOOLEANS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_BOOLEAN, Nullness.NOT_NULL, value.length, index -> toBooleanValue(value[index]));
	}

	public static ArrayValue toByteArrayValue(byte[] value) {
		if (value == null)
			return ArrayValue.VAL_BYTES_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_BYTE, Nullness.NOT_NULL, value.length, index -> toByteValue(value[index]));
	}

	public static ArrayValue toShortArrayValue(short[] value) {
		if (value == null)
			return ArrayValue.VAL_SHORTS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_SHORT, Nullness.NOT_NULL, value.length, index -> toShortValue(value[index]));
	}

	public static ArrayValue toCharArrayValue(char[] value) {
		if (value == null)
			return ArrayValue.VAL_CHARS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_CHAR, Nullness.NOT_NULL, value.length, index -> toCharValue(value[index]));
	}

	public static ArrayValue toIntArrayValue(int[] value) {
		if (value == null)
			return ArrayValue.VAL_INTS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_INT, Nullness.NOT_NULL, value.length, index -> toIntValue(value[index]));
	}

	public static ArrayValue toLongArrayValue(long[] value) {
		if (value == null)
			return ArrayValue.VAL_LONGS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_LONG, Nullness.NOT_NULL, value.length, index -> toLongValue(value[index]));
	}

	public static ArrayValue toFloatArrayValue(float[] value) {
		if (value == null)
			return ArrayValue.VAL_FLOATS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_FLOAT, Nullness.NOT_NULL, value.length, index -> toFloatValue(value[index]));
	}

	public static ArrayValue toDoubleArrayValue(double[] value) {
		if (value == null)
			return ArrayValue.VAL_DOUBLES_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_DOUBLE, Nullness.NOT_NULL, value.length, index -> toDoubleValue(value[index]));
	}

	public static ArrayValue toCharSequenceArrayValue(CharSequence[] value) {
		if (value == null)
			return ArrayValue.VAL_STRINGS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_STRING, Nullness.NOT_NULL, value.length, index -> ObjectValue.string(String.valueOf(value[index])));
	}

	public static ArrayValue toStringArrayValue(String[] value) {
		if (value == null)
			return ArrayValue.VAL_STRINGS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_STRING, Nullness.NOT_NULL, value.length, index -> ObjectValue.string(value[index]));
	}

	public static ArrayValue toObjectArrayValue(Object[] value) {
		if (value == null)
			return ArrayValue.VAL_OBJECTS_NULL;
		return new ArrayValueImpl(Types.ARRAY_1D_OBJECT, Nullness.NOT_NULL, value.length, index -> toObjectValue(value[index]));
	}

	private static int getKnownArrayLength(ArrayValue value) {
		OptionalInt length = value.getFirstDimensionLength();
		if (length.isEmpty() || !value.hasKnownValue())
			throw new IllegalArgumentException("Array value does not have known length");
		return length.getAsInt();
	}

	private static <T> T hostArray(ReValue value, Class<T> type) {
		throw new IllegalArgumentException("Expected host-backed " + type.getTypeName() + ": " + value);
	}
}
