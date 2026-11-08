package dev.joyel.zbyte.analysis.value;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.value.impl.FloatValueImpl;

import java.util.OptionalDouble;

public non-sealed interface FloatValue extends ReValue {
	FloatValue UNKNOWN = new FloatValueImpl();
	FloatValue VAL_MAX = new FloatValueImpl(Float.MAX_VALUE);
	FloatValue VAL_MIN = new FloatValueImpl(Float.MIN_VALUE);
	FloatValue VAL_M1 = new FloatValueImpl(-1);
	FloatValue VAL_0 = new FloatValueImpl(0);
	FloatValue VAL_1 = new FloatValueImpl(1);
	FloatValue VAL_2 = new FloatValueImpl(2);

	static FloatValue of(float value) {
		if (value == 0) return VAL_0;
		else if (value == 1) return VAL_1;
		else if (value == -1) return VAL_M1;
		else if (value == 2) return VAL_2;
		else if (value == Float.MAX_VALUE) return VAL_MAX;
		else if (value == Float.MIN_VALUE) return VAL_MIN;
		return new FloatValueImpl(value);
	}

	OptionalDouble value();

	@Override
	default boolean hasKnownValue() {
		return value().isPresent();
	}

	@Override
	default Type type() {
		return Type.FLOAT_TYPE;
	}

	@Override
	default int getSize() {
		return 1;
	}

	default boolean isEqualTo(float value) {
		return value().isPresent() && value().getAsDouble() == value;
	}

	default boolean isLessThan(float value) {
		return value().isPresent() && value().getAsDouble() < value;
	}

	default boolean isLessThanOrEqual(float value) {
		return value().isPresent() && value().getAsDouble() <= value;
	}

	default boolean isGreaterThan(float value) {
		return value().isPresent() && value().getAsDouble() > value;
	}

	default boolean isGreaterThanOrEqual(float value) {
		return value().isPresent() && value().getAsDouble() >= value;
	}

	default FloatValue add(FloatValue other) {
		OptionalDouble value = value();
		OptionalDouble otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent())
			return of((float) (value.getAsDouble() + otherValue.getAsDouble()));
		return UNKNOWN;
	}

	default FloatValue sub(FloatValue other) {
		OptionalDouble value = value();
		OptionalDouble otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent())
			return of((float) (value.getAsDouble() - otherValue.getAsDouble()));
		return UNKNOWN;
	}

	default FloatValue mul(FloatValue other) {
		OptionalDouble value = value();
		OptionalDouble otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent())
			return of((float) (value.getAsDouble() * otherValue.getAsDouble()));
		if (isEqualTo(0) || other.isEqualTo(0)) return VAL_0;
		return UNKNOWN;
	}

	default FloatValue div(FloatValue other) {
		OptionalDouble value = value();
		OptionalDouble otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) {
			double otherLiteral = otherValue.getAsDouble();
			if (otherLiteral == 0) return UNKNOWN;
			return of((float) (value.getAsDouble() / otherLiteral));
		}
		return UNKNOWN;
	}

	default IntValue cmpg(FloatValue other) {
		OptionalDouble value = value();
		OptionalDouble otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) {
			float f1 = (float) value.getAsDouble();
			float f2 = (float) otherValue.getAsDouble();
			if (Float.isNaN(f1) || Float.isNaN(f2)) return IntValue.VAL_1;
			return IntValue.of(Float.compare(f1, f2));
		}
		return IntValue.UNKNOWN;
	}

	default IntValue cmpl(FloatValue other) {
		OptionalDouble value = value();
		OptionalDouble otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent()) {
			float f1 = (float) value.getAsDouble();
			float f2 = (float) otherValue.getAsDouble();
			if (Float.isNaN(f1) || Float.isNaN(f2)) return IntValue.VAL_M1;
			return IntValue.of(Float.compare(f1, f2));
		}
		return IntValue.UNKNOWN;
	}

	default FloatValue rem(FloatValue other) {
		OptionalDouble value = value();
		OptionalDouble otherValue = other.value();
		if (value.isPresent() && otherValue.isPresent())
			return of((float) (value.getAsDouble() % otherValue.getAsDouble()));
		return UNKNOWN;
	}

	default FloatValue negate() {
		OptionalDouble value = value();
		if (value.isPresent()) return of((float) -value.getAsDouble());
		return UNKNOWN;
	}

	default IntValue castInt() {
		OptionalDouble value = value();
		if (value.isPresent()) return IntValue.of((int) value.getAsDouble());
		return IntValue.UNKNOWN;
	}

	default DoubleValue castDouble() {
		OptionalDouble value = value();
		if (value.isPresent()) return DoubleValue.of(value.getAsDouble());
		return DoubleValue.UNKNOWN;
	}

	default LongValue castLong() {
		OptionalDouble value = value();
		if (value.isPresent()) return LongValue.of((long) value.getAsDouble());
		return LongValue.UNKNOWN;
	}
}
