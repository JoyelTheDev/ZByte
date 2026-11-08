package dev.joyel.zbyte.analysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Nullness;

public class BoxedLongValueImpl extends ObjectValueBoxImpl<Long> {
	private static final Type TYPE = Type.getType(Long.class);

	public BoxedLongValueImpl(Nullness nullness) {
		super(TYPE, nullness);
	}

	public BoxedLongValueImpl(Long value) {
		super(TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<Long> wrap(Long value) {
		return new BoxedLongValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<Long> wrapUnknown(Nullness nullness) {
		return new BoxedLongValueImpl(nullness);
	}

	@Override
	public int getSize() {

		return 1;
	}
}
