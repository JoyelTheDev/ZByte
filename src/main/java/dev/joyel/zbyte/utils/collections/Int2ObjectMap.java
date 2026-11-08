package dev.joyel.zbyte.utils.collections;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

public class Int2ObjectMap<V> extends AbstractIntKeyMap {
	private Object[] values;

	public Int2ObjectMap() {
		this(DEFAULT_CAPACITY);
	}

	public Int2ObjectMap(int initialCapacity) {
		super(initialCapacity);
		values = new Object[keys.length];
	}

	public Int2ObjectMap(Int2ObjectMap<V> other) {
		super(other.size());

		values = new Object[keys.length];
		other.forEach(this::put);
	}

	public void forEach(IntObjectConsumer<V> consumer) {
		for (int i = 0; i < keys.length; i++)
			if (occupied[i])
				consumer.accept(keys[i], (V) values[i]);
	}

	public V put(int key, V value) {
		int idx = findIndex(key);
		if (occupied[idx]) {
			Object old = values[idx];
			values[idx] = value;
			return (V) old;
		}

		values[idx] = value;
		insertKeyAt(idx, key);
		return null;
	}

	public V get(int key) {
		int idx = findIndex(key);
		return occupied[idx] ? (V) values[idx] : null;
	}

	public V getOrDefault(int key, Object defaultValue) {
		int idx = findIndex(key);
		return (V) (occupied[idx] ? values[idx] : defaultValue);
	}

	public V computeIfAbsent(int key, IntFunction<? extends V> mapping) {
		int idx = findIndex(key);
		if (occupied[idx])
			return (V) values[idx];
		V newValue = mapping.apply(key);
		values[idx] = newValue;
		insertKeyAt(idx, key);
		return newValue;
	}

	public V remove(int key) {
		int idx = findIndex(key);
		if (!occupied[idx])
			return null;
		V old = (V) values[idx];
		removeEntryAt(idx);
		return old;
	}

	public void clear() {
		clearKeys();
		Arrays.fill(values, null);
	}

	@Override
	protected void clearValue(int index) {
		values[index] = null;
	}

	@Override
	protected void moveValue(int from, int to) {
		values[to] = values[from];
	}

	@Override
	protected void resize() {
		int newCap = keys.length * 2;
		int[] oldKeys = keys;
		Object[] oldValues = values;
		boolean[] oldOccupied = occupied;

		allocateTable(newCap);
		values = new Object[newCap];

		for (int i = 0; i < oldKeys.length; i++) {
			if (oldOccupied[i])
				put(oldKeys[i], (V) oldValues[i]);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof Int2ObjectMap<?> other))
			return false;
		if (size() != other.size())
			return false;

		for (int i = 0; i < keys.length; i++) {
			if (!occupied[i])
				continue;
			int key = keys[i];
			Object value = values[i];
			Object thatValue = other.get(key);
			if (!Objects.equals(value, thatValue))
				return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hash = 0;
		for (int i = 0; i < keys.length; i++) {
			if (!occupied[i])
				continue;
			int key = keys[i];
			Object value = values[i];
			hash += Integer.hashCode(key) ^ Objects.hashCode(value);
		}
		return hash;
	}

	@FunctionalInterface
	public interface IntObjectConsumer<V> {
		void accept(int key, V value);
	}
}
