package dev.joyel.zbyte.analysis.value;

import java.util.List;

public interface ThrowableValue extends ObjectValue {

	List<StackTraceElement> getStackTrace();

	Throwable getBackingException();
}
