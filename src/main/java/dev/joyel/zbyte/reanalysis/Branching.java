package dev.joyel.zbyte.reanalysis;

public enum Branching {
	UNKNOWN, TAKEN, NOT_TAKEN;

	public Branching invert() {
		return switch (this) {
			case NOT_TAKEN -> TAKEN;
			case TAKEN -> NOT_TAKEN;
			default -> UNKNOWN;
		};
	}
}
