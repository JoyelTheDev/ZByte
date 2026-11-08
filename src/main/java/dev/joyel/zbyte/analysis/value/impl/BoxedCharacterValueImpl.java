package dev.joyel.zbyte.analysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.analysis.Nullness;

public class BoxedCharacterValueImpl extends ObjectValueBoxImpl<Character> {
	private static final Type TYPE = Type.getType(Character.class);

	public BoxedCharacterValueImpl(Nullness nullness) {
		super(TYPE, nullness);
	}

	public BoxedCharacterValueImpl(Character value) {
		super(TYPE, value);
	}

	@Override
	protected ObjectValueBoxImpl<Character> wrap(Character value) {
		return new BoxedCharacterValueImpl(value);
	}

	@Override
	protected ObjectValueBoxImpl<Character> wrapUnknown(Nullness nullness) {
		return new BoxedCharacterValueImpl(nullness);
	}

	@Override
	public int getSize() {

		return 1;
	}
}
