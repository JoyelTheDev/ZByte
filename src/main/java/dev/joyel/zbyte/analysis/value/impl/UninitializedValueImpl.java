package dev.joyel.zbyte.analysis.value.impl;

import dev.joyel.zbyte.analysis.value.ReValue;
import dev.joyel.zbyte.analysis.value.UninitializedValue;

public class UninitializedValueImpl implements UninitializedValue {
	public static final UninitializedValue UNINITIALIZED_VALUE = new UninitializedValueImpl();

	private UninitializedValueImpl() {}

	@Override
	public String toString() {
		return ".";
	}

	@Override
	public ReValue mergeWith(ReValue other) {
		return this;
	}
}
