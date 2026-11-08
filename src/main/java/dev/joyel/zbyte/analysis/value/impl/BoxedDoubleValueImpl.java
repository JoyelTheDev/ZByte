package dev.joyel.zbyte.analysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Nullness;

public class BoxedDoubleValueImpl extends ObjectValueBoxImpl<Double> {
	private static final Type TYPE = Type.getType(Double.class);

	public BoxedDoubleValueImpl(Nullness nullness) {
		super(TYPE, nullness);
	}

	public BoxedDoubleValueImpl(Double value) {
		super(TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<Double> wrap(Double value) {
		return new BoxedDoubleValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<Double> wrapUnknown(Nullness nullness) {
		return new BoxedDoubleValueImpl(nullness);
	}

	@Override
	public int getSize() {

		return 1;
	}
}
