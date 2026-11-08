package dev.joyel.zbyte.analysis.value.impl;

import dev.joyel.zbyte.analysis.value.IllegalValueException;
import dev.joyel.zbyte.analysis.value.IntValue;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.analysis.value.UninitializedValue;

import java.util.OptionalInt;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class IntValueImpl implements IntValue {
	private final OptionalInt value;

	public IntValueImpl() {
		this.value = OptionalInt.empty();
	}

	public IntValueImpl(int value) {
		this.value = OptionalInt.of(value);
	}

	@Override
	public OptionalInt value() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		IntValueImpl other = (IntValueImpl) o;

		return value.equals(other.value);
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

	@Override
	public String toString() {
		return type().getInternalName() + ":" + (value.isPresent() ? value.getAsInt() : "?");
	}

	@Override
	public ReValue mergeWith(ReValue other) throws IllegalValueException {
		if (other == UninitializedValue.UNINITIALIZED_VALUE)
			return other;
		else if (other instanceof IntValue otherInt) {
			if (value().isPresent() && otherInt.value().isPresent()) {
				int i = value().getAsInt();
				int otherI = otherInt.value().getAsInt();
				if (i == otherI)
					return IntValue.of(i);
			}
			return IntValue.UNKNOWN;
		}
		throw new IllegalValueException("Cannot merge with: " + other);
	}
}
