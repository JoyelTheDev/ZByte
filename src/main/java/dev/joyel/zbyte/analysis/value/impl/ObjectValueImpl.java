package dev.joyel.zbyte.analysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Nullness;
import dev.joyel.zbyte.analysis.value.IllegalValueException;
import dev.joyel.zbyte.analysis.value.ObjectValue;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.analysis.value.UninitializedValue;

public class ObjectValueImpl implements ObjectValue {
	private final Type type;
	private final Nullness nullness;

	public ObjectValueImpl(Type type, Nullness nullness) {
		this.type = type;
		this.nullness = nullness;
	}

	@Override
	public boolean hasKnownValue() {
		return nullness == Nullness.NULL;
	}

	@Override
	public Type type() {
		return type;
	}

	@Override
	public ReValue mergeWith(ReValue other) throws IllegalValueException {
		if (other == UninitializedValue.UNINITIALIZED_VALUE)
			return other;
		else if (other instanceof ObjectValue otherObject)
			return ObjectValue.object(type, nullness().mergeWith(otherObject.nullness()));
		throw new IllegalValueException("Cannot merge with: " + other);
	}

	@Override
	public Nullness nullness() {
		return nullness;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ObjectValueImpl other = (ObjectValueImpl) o;

		return type.equals(other.type) && nullness.equals(other.nullness);
	}

	@Override
	public int hashCode() {
		int result = type.hashCode();
		result = 31 * result + nullness.hashCode();
		return result;
	}

	@Override
	public String toString() {
		return type.getInternalName();
	}
}
