package dev.joyel.zbyte.reanalysis.value;

import dev.joyel.zbyte.reanalysis.Nullness;
import dev.joyel.zbyte.reanalysis.value.impl.StringValueImpl;

import java.util.Optional;

public interface StringValue extends ObjectValue {
	StringValue VAL_STRING = new StringValueImpl(Nullness.NOT_NULL);
	StringValue VAL_STRING_MAYBE_NULL = new StringValueImpl(Nullness.UNKNOWN);
	StringValue VAL_STRING_NULL = new StringValueImpl(Nullness.NULL);
	StringValue VAL_STRING_EMPTY = new StringValueImpl("");
	StringValue VAL_STRING_SPACE = new StringValueImpl(" ");

	Optional<String> getText();

	default boolean isEqualTo(String otherValue) {
		Optional<String> text = getText();
		return text.map(s -> s.equals(otherValue))
				.orElseGet(() -> otherValue == null && isNull());
	}

	default boolean isNotEqualTo(String otherValue) {
		Optional<String> text = getText();
		if (text.isPresent())
			return !text.get().equals(otherValue);
		if (otherValue == null)
			return isNotNull();
		return isNull();
	}
}
