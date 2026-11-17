package dev.joyel.zbyte.reanalysis.value;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.analysis.Value;
import dev.joyel.zbyte.reanalysis.Nullness;
import dev.joyel.zbyte.reanalysis.ReInterpreter;

public sealed interface ReValue extends Value permits IntValue, FloatValue, DoubleValue, LongValue, ObjectValue, UninitializedValue {

	static ReValue ofConstant(Object value) throws IllegalValueException {
		return switch (value) {
			case Boolean b -> IntValue.of(b ? 1 : 0);
			case Character c -> IntValue.of(c);
			case Byte b -> IntValue.of(b);
			case Short s -> IntValue.of(s);
			case Integer i -> IntValue.of(i);
			case Float f -> FloatValue.of(f);
			case Long l -> LongValue.of(l);
			case Double d -> DoubleValue.of(d);
			case String s -> ObjectValue.string(s);
			case Handle handle -> ObjectValue.VAL_METHOD_HANDLE;
			case Type type -> {
				int sort = type.getSort();
				if (sort == Type.OBJECT || sort == Type.ARRAY)
					yield ObjectValue.VAL_CLASS;
				if (sort == Type.METHOD)
					yield ObjectValue.VAL_METHOD_TYPE;
				throw new IllegalValueException("Illegal LDC value " + value);
			}
			case ConstantDynamic constantDynamic -> {
				Type dynamicType = Type.getType(constantDynamic.getDescriptor());
				ReValue dynamicValue = ofType(dynamicType, Nullness.NOT_NULL);
				if (dynamicValue == null)
					throw new IllegalValueException("Illegal LDC dynamic value descriptor " + dynamicType);
				yield dynamicValue;
			}
			case null, default -> throw new IllegalValueException("Illegal LDC value " + value);
		};
	}

	static ReValue ofType(Type type, Nullness nullness) throws IllegalValueException {
		if (type == null)
			return UninitializedValue.UNINITIALIZED_VALUE;
		return switch (type.getSort()) {
			case Type.VOID -> null;
			case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> IntValue.UNKNOWN;
			case Type.FLOAT -> FloatValue.UNKNOWN;
			case Type.LONG -> LongValue.UNKNOWN;
			case Type.DOUBLE -> DoubleValue.UNKNOWN;
			case Type.ARRAY -> ArrayValue.of(type, nullness);
			case Type.OBJECT -> ObjectValue.object(type, nullness);
			default -> throw new IllegalValueException("Invalid type for new value: " + type);
		};
	}

	static ReValue ofTypeDefaultValue(Type type) throws IllegalValueException {
		return switch (type.getSort()) {
			case Type.VOID -> throw new IllegalValueException("Cannot create default value for 'void' type");
			case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> IntValue.VAL_0;
			case Type.FLOAT -> FloatValue.VAL_0;
			case Type.LONG -> LongValue.VAL_0;
			case Type.DOUBLE -> DoubleValue.VAL_0;
			case Type.ARRAY -> ArrayValue.of(type, Nullness.NULL);
			case Type.OBJECT -> ObjectValue.object(type, Nullness.NULL);
			default -> throw new IllegalValueException("Invalid type for new default value: " + type);
		};
	}

	static boolean isPrimitiveEqualTo(ReValue value, int v) {
		return switch (value) {
			case DoubleValue x -> x.isEqualTo(v);
			case FloatValue x -> x.isEqualTo(v);
			case IntValue x -> x.isEqualTo(v);
			case LongValue x -> x.isEqualTo(v);
			case ObjectValue _, UninitializedValue _ -> false;
		};
	}

	boolean hasKnownValue();

	Type type();

	ReValue mergeWith(ReValue other) throws IllegalValueException;
}
