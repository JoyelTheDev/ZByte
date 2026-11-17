package dev.joyel.zbyte.reanalysis.value.impl;

import org.objectweb.asm.Type;
import dev.joyel.zbyte.reanalysis.Nullness;
import dev.joyel.zbyte.reanalysis.value.IllegalValueException;
import dev.joyel.zbyte.reanalysis.value.ReValue;
import dev.joyel.zbyte.reanalysis.value.ThrowableValue;

import java.util.List;

public class ThrowableValueImpl extends ObjectValueImpl implements ThrowableValue {
	private final List<StackTraceElement> stackTrace;
	private final Throwable backingException;

	public ThrowableValueImpl(Type type,
	                          List<StackTraceElement> stackTrace,
	                          Throwable backingException) {
		super(type, Nullness.NOT_NULL);
		this.stackTrace = List.copyOf(stackTrace);
		this.backingException = backingException;
	}

	@Override
	public List<StackTraceElement> getStackTrace() {
		return stackTrace;
	}

	@Override
	public Throwable getBackingException() {
		return backingException;
	}

	@Override
	public boolean hasKnownValue() {
		return true;
	}

	@Override
	public ReValue mergeWith(ReValue other) throws IllegalValueException {
		if (other == this || other instanceof ThrowableValue value && stackTrace.equals(value.getStackTrace()))
			return this;
		return super.mergeWith(other);
	}
}
