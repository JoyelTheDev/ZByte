package dev.joyel.zbyte.analysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Nullness;

public class BoxedIntegerValueImpl extends ObjectValueBoxImpl<Integer> {
	private static final Type TYPE = Type.getType(Integer.class);

	public BoxedIntegerValueImpl(Nullness nullness) {
		super(TYPE, nullness);
	}

	public BoxedIntegerValueImpl(Integer value) {
		super(TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<Integer> wrap(Integer value) {
		return new BoxedIntegerValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<Integer> wrapUnknown(Nullness nullness) {
		return new BoxedIntegerValueImpl(nullness);
	}

	@Override
	public int getSize() {

		return 1;
	}
}
