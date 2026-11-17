package dev.joyel.zbyte.reanalysis.value;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.reanalysis.value.impl.UninitializedValueImpl;

public non-sealed interface UninitializedValue extends ReValue {
	UninitializedValue UNINITIALIZED_VALUE = UninitializedValueImpl.UNINITIALIZED_VALUE;

	@Override
	default boolean hasKnownValue() {
		return false;
	}

	@Override
	default Type type() {
		return null;
	}

	@Override
	default int getSize() {
		return 1;
	}
}
