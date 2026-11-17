package dev.joyel.zbyte.reanalysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.reanalysis.Nullness;

public class BoxedBooleanValueImpl extends ObjectValueBoxImpl<Boolean> {
	private static final Type TYPE = Type.getType(Boolean.class);

	public BoxedBooleanValueImpl(Nullness nullness) {
		super(TYPE, nullness);
	}

	public BoxedBooleanValueImpl(Boolean value) {
		super(TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<Boolean> wrap(Boolean value) {
		return new BoxedBooleanValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<Boolean> wrapUnknown(Nullness nullness) {
		return new BoxedBooleanValueImpl(nullness);
	}

	@Override
	public int getSize() {

		return 1;
	}
}
