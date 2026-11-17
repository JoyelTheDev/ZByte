package dev.joyel.zbyte.reanalysis.value;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.reanalysis.value.impl.IntValueImpl;

import java.util.OptionalInt;

public non-sealed interface IntValue extends ReValue {
	IntValue UNKNOWN = new IntValueImpl();
	IntValue VAL_MAX = new IntValueImpl(Integer.MAX_VALUE);
	IntValue VAL_MIN = new IntValueImpl(Integer.MIN_VALUE);
	IntValue VAL_M1 = new IntValueImpl(-1);
	IntValue VAL_0 = new IntValueImpl(0);
	IntValue VAL_1 = new IntValueImpl(1);
	IntValue VAL_2 = new IntValueImpl(2);
	IntValue VAL_3 = new IntValueImpl(3);
	IntValue VAL_4 = new IntValueImpl(4);
	IntValue VAL_5 = new IntValueImpl(5);

	static IntValue of(int value) {
		return switch (value) {
			case Integer.MAX_VALUE -> VAL_MAX;
			case Integer.MIN_VALUE -> VAL_MIN;
			case -1 -> VAL_M1;
			case 0 -> VAL_0;
			case 1 -> VAL_1;
			case 2 -> VAL_2;
			case 3 -> VAL_3;
			case 4 -> VAL_4;
			case 5 -> VAL_5;
			default -> new IntValueImpl(value);
		};
	}

	OptionalInt value();

	@Override
	default boolean hasKnownValue() {
		return value().isPresent();
	}

	@Override
	default Type type() {
		return Type.INT_TYPE;
	}

	@Override
	default int getSize() {
		return 1;
	}

	default boolean isEqualTo(int value) {
		return value().isPresent() && value().getAsInt() == value;
	}

	default boolean isEqualTo(IntValue otherValue) {
		return value().isPresent() && otherValue.value().isPresent()
				&& value().getAsInt() == otherValue.value().getAsInt();
	}

	default boolean isNotEqualTo(int value) {
		return value().isPresent() && value().getAsInt() != value;
	}

	default boolean isNotEqualTo(IntValue otherValue) {
		return value().isPresent() && otherValue.value().isPresent()
				&& value().getAsInt() != otherValue.value().getAsInt();
	}

	default boolean isLessThan(int value) {
		return value().isPresent() && value().getAsInt() < value;
	}

	default boolean isLessThan(IntValue otherValue) {
		return value().isPresent() && otherValue.value().isPresent()
				&& value().getAsInt() < otherValue.value().getAsInt();
	}

	default boolean isLessThanOrEqual(int value) {
		return value().isPresent() && value().getAsInt() <= value;
	}

	default boolean isLessThanOrEqual(IntValue otherValue) {
		return value().isPresent() && otherValue.value().isPresent()
				&& value().getAsInt() <= otherValue.value().getAsInt();
	}

	default boolean isGreaterThan(int value) {
		return value().isPresent() && value().getAsInt() > value;
	}

	default boolean isGreaterThan(IntValue otherValue) {
		return value().isPresent() && otherValue.value().isPresent()
				&& value().getAsInt() > otherValue.value().getAsInt();
	}

	default boolean isGreaterThanOrEqual(int value) {
		return value().isPresent() && value().getAsInt() >= value;
	}

	default boolean isGreaterThanOrEqual(IntValue otherValue) {
		return value().isPresent() && otherValue.value().isPresent()
				&& value().getAsInt() >= otherValue.value().getAsInt();
	}

	default IntValue add(int incr) {
		OptionalInt value = value();
		if (value.isPresent()) return of(value.getAsInt() + incr);
		return UNKNOWN;
	}

	default IntValue add(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() + otherValue.getAsInt());
		return UNKNOWN;
	}

	default IntValue sub(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() - otherValue.getAsInt());
		return UNKNOWN;
	}

	default IntValue mul(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() * otherValue.getAsInt());
		if (isEqualTo(0) || other.isEqualTo(0)) return VAL_0;
		return UNKNOWN;
	}

	default IntValue div(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) {
			int otherLiteral = otherValue.getAsInt();
			if (otherLiteral == 0) return UNKNOWN;
			return of(value.getAsInt() / otherLiteral);
		}
		return UNKNOWN;
	}

	default IntValue and(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() & otherValue.getAsInt());
		if (isEqualTo(0) || other.isEqualTo(0)) return VAL_0;
		return UNKNOWN;
	}

	default IntValue or(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() | otherValue.getAsInt());
		if (isEqualTo(-1) || other.isEqualTo(-1)) return VAL_M1;
		return UNKNOWN;
	}

	default IntValue xor(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() ^ otherValue.getAsInt());
		return UNKNOWN;
	}

	default IntValue rem(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) {
			int otherLiteral = otherValue.getAsInt();
			if (otherLiteral == 0) return UNKNOWN;
			return of(value.getAsInt() % otherLiteral);
		}
		if (other.isEqualTo(1)) return VAL_0;
		return UNKNOWN;
	}

	default IntValue shl(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() << otherValue.getAsInt());
		return UNKNOWN;
	}

	default IntValue shr(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() >> otherValue.getAsInt());
		return UNKNOWN;
	}

	default IntValue ushr(IntValue other) {
		OptionalInt value = value();
		OptionalInt otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) return of(value.getAsInt() >>> otherValue.getAsInt());
		return UNKNOWN;
	}

	default IntValue negate() {
		OptionalInt value = value();
		if (value.isPresent()) return of(-value.getAsInt());
		return UNKNOWN;
	}

	default IntValue castByte() {
		OptionalInt value = value();
		if (value.isPresent()) return of((byte) value.getAsInt());
		return UNKNOWN;
	}

	default IntValue castChar() {
		OptionalInt value = value();
		if (value.isPresent()) return of((char) value.getAsInt());
		return UNKNOWN;
	}

	default IntValue castShort() {
		OptionalInt value = value();
		if (value.isPresent()) return of((short) value.getAsInt());
		return UNKNOWN;
	}

	default FloatValue castFloat() {
		OptionalInt value = value();
		if (value.isPresent()) return FloatValue.of(value.getAsInt());
		return FloatValue.UNKNOWN;
	}

	default DoubleValue castDouble() {
		OptionalInt value = value();
		if (value.isPresent()) return DoubleValue.of(value.getAsInt());
		return DoubleValue.UNKNOWN;
	}

	default LongValue castLong() {
		OptionalInt value = value();
		if (value.isPresent()) return LongValue.of(value.getAsInt());
		return LongValue.UNKNOWN;
	}
}
