package dev.joyel.zbyte.reanalysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.reanalysis.Nullness;

public class BoxedByteValueImpl extends ObjectValueBoxImpl<Byte> {
	private static final Type TYPE = Type.getType(Byte.class);

	public BoxedByteValueImpl(Nullness nullness) {
		super(TYPE, nullness);
	}

	public BoxedByteValueImpl(Byte value) {
		super(TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<Byte> wrap(Byte value) {
		return new BoxedByteValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<Byte> wrapUnknown(Nullness nullness) {
		return new BoxedByteValueImpl(nullness);
	}

	@Override
	public int getSize() {

		return 1;
	}
}
