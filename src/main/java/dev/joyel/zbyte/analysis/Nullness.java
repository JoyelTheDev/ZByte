package dev.joyel.zbyte.analysis;

public enum Nullness {
	UNKNOWN, NULL, NOT_NULL;

	public Nullness mergeWith(Nullness other) {
		if (this != other) return UNKNOWN;
		return this;
	}
}
