package dev.joyel.zbyte.utils.collections;

import java.util.Arrays;

abstract class AbstractIntKeyMap {
	protected static final int DEFAULT_CAPACITY = 16;
	protected static final float LOAD_FACTOR = 0.75f;

	protected int[] keys;
	protected boolean[] occupied;
	protected int size;
	protected int threshold;

	protected AbstractIntKeyMap() {
		this(DEFAULT_CAPACITY);
	}

	protected AbstractIntKeyMap(int initialCapacity) {
		allocateTable(Math.max(2, nextPowerOfTwo(initialCapacity)));
	}

	public final boolean containsKey(int key) {
		return occupied[findIndex(key)];
	}

	public final int[] keys() {
		int[] result = new int[size];
		int idx = 0;
		for (int i = 0; i < keys.length; i++)
			if (occupied[i])
				result[idx++] = keys[i];
		return result;
	}

	public final int size() {
		return size;
	}

	protected final void allocateTable(int capacity) {
		keys = new int[capacity];
		occupied = new boolean[capacity];
		size = 0;
		threshold = (int) (capacity * LOAD_FACTOR);
	}

	protected final void clearKeys() {
		size = 0;
		Arrays.fill(occupied, false);
	}

	protected final int findIndex(int key) {
		int len = keys.length;
		int idx = hash(key) & (len - 1);
		while (occupied[idx] && keys[idx] != key)
			idx = (idx + 1) & (len - 1);
		return idx;
	}

	protected final void insertKeyAt(int index, int key) {
		keys[index] = key;
		occupied[index] = true;
		size++;
		if (size > threshold)
			resize();
	}

	protected final void removeEntryAt(int index) {
		size--;
		closeDeletion(index);
	}

	protected int hash(int key) {
		return key ^ (key >>> 16);
	}

	private void closeDeletion(int deletedIndex) {
		int index = deletedIndex;
		int len = keys.length;

		for (int next = (index + 1) & (len - 1); occupied[next]; next = (next + 1) & (len - 1)) {
			int slot = hash(keys[next]) & (len - 1);
			if ((next < slot && (slot <= index || index <= next)) || (slot <= index && index <= next)) {
				keys[index] = keys[next];
				occupied[index] = true;
				moveValue(next, index);
				index = next;
			}
		}

		occupied[index] = false;
		clearValue(index);
	}

	protected abstract void clearValue(int index);

	protected abstract void moveValue(int from, int to);

	protected abstract void resize();

	@Override
	public abstract boolean equals(Object o);

	@Override
	public abstract int hashCode();

	private static int nextPowerOfTwo(int v) {
		v--;
		v |= v >> 1;
		v |= v >> 2;
		v |= v >> 4;
		v |= v >> 8;
		v |= v >> 16;
		return v + 1;
	}
}
