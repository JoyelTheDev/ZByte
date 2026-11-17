package dev.joyel.zbyte.reanalysis.value.impl;

import dev.joyel.zbyte.reanalysis.value.DoubleValue;
import dev.joyel.zbyte.reanalysis.value.IllegalValueException;
import dev.joyel.zbyte.reanalysis.value.ReValue;
import dev.joyel.zbyte.reanalysis.value.UninitializedValue;

import java.util.OptionalDouble;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class DoubleValueImpl implements DoubleValue {
	private final OptionalDouble value;

	public DoubleValueImpl(double value) {
		this.value = OptionalDouble.of(value);
	}

	public DoubleValueImpl() {
		this.value = OptionalDouble.empty();
	}

	@Override
	public OptionalDouble value() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		DoubleValueImpl other = (DoubleValueImpl) o;

		return value.equals(other.value);
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

	@Override
	public String toString() {
		return type().getInternalName() + ":" + (value.isPresent() ? value.getAsDouble() : "?");
	}

	@Override
	public ReValue mergeWith(ReValue other) throws IllegalValueException {
		if (other == UninitializedValue.UNINITIALIZED_VALUE)
			return other;
		else if (other instanceof DoubleValue otherDouble) {
			if (value().isPresent() && otherDouble.value().isPresent()) {
				double d = value().getAsDouble();
				double otherD = otherDouble.value().getAsDouble();
				if (d == otherD)
					return DoubleValue.of(d);
			}
			return DoubleValue.UNKNOWN;
		}
		throw new IllegalValueException("Cannot merge with: " + other);
	}
}
