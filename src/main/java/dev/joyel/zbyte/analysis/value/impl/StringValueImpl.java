package dev.joyel.zbyte.analysis.value.impl;

import dev.joyel.zbyte.analysis.Types;
import dev.joyel.zbyte.analysis.Nullness;
import dev.joyel.zbyte.analysis.value.StringValue;

import java.util.Optional;

public class StringValueImpl extends ObjectValueBoxImpl<String> implements StringValue {
	public StringValueImpl(Nullness nullness) {
		super(Types.STRING_TYPE, nullness);
	}

	public StringValueImpl(String value) {
		super(Types.STRING_TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<String> wrap(String value) {
		return new StringValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<String> wrapUnknown(Nullness nullness) {
		return new StringValueImpl(nullness);
	}

	@Override
	public Optional<String> getText() {
		return value();
	}
}
