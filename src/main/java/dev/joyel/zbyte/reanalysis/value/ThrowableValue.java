package dev.joyel.zbyte.reanalysis.value;

import java.util.List;

public interface ThrowableValue extends ObjectValue {

	List<StackTraceElement> getStackTrace();

	Throwable getBackingException();
}
