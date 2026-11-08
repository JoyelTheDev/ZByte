package dev.joyel.zbyte.analysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Nullness;
import dev.joyel.zbyte.analysis.value.ArrayValue;
import dev.joyel.zbyte.analysis.value.IllegalValueException;
import dev.joyel.zbyte.analysis.value.ObjectValue;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.analysis.value.UninitializedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class ArrayValueMutableImpl implements ArrayValue {
	private final Type type;
	private final Nullness nullness;
	private final OptionalInt length;
	private final List<ReValue> contents;
	private boolean containsSubArray;

	private ArrayValueMutableImpl(ArrayValue source) {
		type = source.type();
		nullness = source.nullness();
		length = source.getFirstDimensionLength();

		if (length.isPresent()) {
			contents = new ArrayList<>(length.getAsInt());
			for (int i = 0; i < length.getAsInt(); i++) {
				ReValue value = Objects.requireNonNull(source.getValue(i));
				if (value instanceof ArrayValue array) {
					containsSubArray = true;
					contents.add(wrap(array));
				} else {
					contents.add(value);
				}
			}
		} else {
			contents = null;
			containsSubArray = false;
		}
	}

	public static ArrayValue wrap(ArrayValue value) {
		return value instanceof ArrayValueMutableImpl ? value : new ArrayValueMutableImpl(value);
	}

	@Override
	public ArrayValue setValue(int index, ReValue value) {

		if (nullness == Nullness.NOT_NULL
				&& length.isPresent()
				&& contents != null
				&& index >= 0
				&& index < contents.size()) {
			if (value instanceof ArrayValue array) {
				containsSubArray = true;
				contents.set(index, wrap(array));
			} else {
				contents.set(index, value);
			}
		}
		return this;
	}

	@Override
	public ArrayValue updatedCopyIfContained(ReValue originalValue, ReValue updatedValue) {
		if (originalValue instanceof ArrayValue && !containsTrackedSubArray())
			return this;

		if (nullness == Nullness.UNKNOWN || nullness == Nullness.NULL || length.isEmpty() || contents == null)
			return this;

		int size = contents.size();
		for (int i = 0; i < size; i++) {
			ReValue content = contents.get(i);

			if (content == originalValue)
				return setValue(i, updatedValue);

			if (content instanceof ArrayValue subArray) {
				ArrayValue updatedSubArray = subArray.updatedCopyIfContained(originalValue, updatedValue);
				if (subArray != updatedSubArray)
					return setValue(i, updatedSubArray);
			}
		}

		return this;
	}

	@Override
	public boolean containsTrackedSubArray() {
		return containsSubArray;
	}

	@Override
	public Type type() {
		return type;
	}

	@Override
	public boolean hasKnownValue() {
		return nullness == Nullness.NOT_NULL
				&& length.isPresent()
				&& contents != null
				&& contents.stream().allMatch(value -> value.hasKnownValue()
				|| value instanceof ObjectValue object && object.isNull());
	}

	@Override
	public ReValue mergeWith(ReValue other) throws IllegalValueException {
		if (other == UninitializedValue.UNINITIALIZED_VALUE)
			return other;
		ArrayValueImpl snapshot = length.isPresent()
				? new ArrayValueImpl(type, nullness, length.getAsInt(), contents::get)
				: new ArrayValueImpl(type, nullness);
		return snapshot.mergeWith(other);
	}

	@Override
	public Nullness nullness() {
		return nullness;
	}

	@Override
	public OptionalInt getFirstDimensionLength() {
		return length;
	}

	@Override
	public ReValue getValue(int index) {
		if (index < 0 || index >= length.orElse(0)) {
			try {
				return Objects.requireNonNull(ReValue.ofType(type.getElementType(), Nullness.UNKNOWN));
			} catch (IllegalValueException ex) {
				throw new IllegalStateException("Failed creating unknown array element value", ex);
			}
		}
		return contents == null ? null : contents.get(index);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ArrayValueMutableImpl that)) return false;

		if (!type.equals(that.type)) return false;
		if (nullness != that.nullness) return false;
		if (!length.equals(that.length)) return false;
		return Objects.equals(contents, that.contents);
	}

	@Override
	public int hashCode() {
		int result = type.hashCode();
		result = 31 * result + nullness.hashCode();
		result = 31 * result + length.hashCode();
		result = 31 * result + (contents != null ? contents.hashCode() : 0);
		return result;
	}

	@Override
	public String toString() {
		return type().getInternalName();
	}
}
