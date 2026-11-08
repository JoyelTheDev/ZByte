package dev.joyel.zbyte.analysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Nullness;
import dev.joyel.zbyte.analysis.value.IllegalValueException;
import dev.joyel.zbyte.analysis.value.ObjectValue;
import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.analysis.value.UninitializedValue;

import java.util.Objects;
import java.util.Optional;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public abstract class ObjectValueBoxImpl<T> extends ObjectValueImpl {
	private final Optional<T> value;

	public ObjectValueBoxImpl(Type type, Nullness nullness) {
		super(type, nullness);
		value = Optional.empty();
	}

	public ObjectValueBoxImpl(Type type, T value) {
		super(type, value == null ? Nullness.NULL : Nullness.NOT_NULL);
		this.value = Optional.ofNullable(value);
	}

	protected abstract ObjectValueBoxImpl<T> wrap(T value);

	protected abstract ObjectValueBoxImpl<T> wrapUnknown(Nullness nullness);

	@Override
	public boolean hasKnownValue() {
		return nullness() == Nullness.NOT_NULL && value.isPresent();
	}

	public T unbox() {
		return value().orElseThrow();
	}

	public Optional<T> value() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ObjectValueBoxImpl<?> other = (ObjectValueBoxImpl<?>) o;

		return value().equals(other.value());
	}

	@Override
	public int hashCode() {
		int value = type().hashCode();
		value = 31 * value + value().hashCode();
		return value;
	}

	@Override
	public String toString() {
		return type().getInternalName() + ":" + (hasKnownValue() ? unbox() : "<?>");
	}

	@Override
	@SuppressWarnings("unchecked")
	public ReValue mergeWith(ReValue other) throws IllegalValueException {
		if (other == UninitializedValue.UNINITIALIZED_VALUE)
			return other;
		else if (other instanceof ObjectValueBoxImpl<?> otherBoxed) {
			if (type().equals(otherBoxed.type()) && value().isPresent() && otherBoxed.value().isPresent()) {
				T v = value().get();
				T otherV = (T) otherBoxed.value().get();
				if (Objects.equals(v, otherV))
					return wrap(v);
			}
			return wrapUnknown(nullness().mergeWith(otherBoxed.nullness()));
		} else if (other instanceof ObjectValue otherObject) {
			return ObjectValue.object(type(), nullness().mergeWith(otherObject.nullness()));
		}
		throw new IllegalValueException("Cannot merge with: " + other);
	}
}
