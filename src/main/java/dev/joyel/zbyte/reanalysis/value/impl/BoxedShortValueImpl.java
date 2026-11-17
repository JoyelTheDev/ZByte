package dev.joyel.zbyte.reanalysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.reanalysis.Nullness;

public class BoxedShortValueImpl extends ObjectValueBoxImpl<Short> {
	private static final Type TYPE = Type.getType(Short.class);

	public BoxedShortValueImpl(Nullness nullness) {
		super(TYPE, nullness);
	}

	public BoxedShortValueImpl(Short value) {
		super(TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<Short> wrap(Short value) {
		return new BoxedShortValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<Short> wrapUnknown(Nullness nullness) {
		return new BoxedShortValueImpl(nullness);
	}

	@Override
	public int getSize() {

		return 1;
	}
}
