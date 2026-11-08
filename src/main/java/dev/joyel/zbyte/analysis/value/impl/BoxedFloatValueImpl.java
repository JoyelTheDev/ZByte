package dev.joyel.zbyte.analysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Nullness;

public class BoxedFloatValueImpl extends ObjectValueBoxImpl<Float> {
	private static final Type TYPE = Type.getType(Float.class);

	public BoxedFloatValueImpl(Nullness nullness) {
		super(TYPE, nullness);
	}

	public BoxedFloatValueImpl(Float value) {
		super(TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<Float> wrap(Float value) {
		return new BoxedFloatValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<Float> wrapUnknown(Nullness nullness) {
		return new BoxedFloatValueImpl(nullness);
	}

	@Override
	public int getSize() {

		return 1;
	}
}
