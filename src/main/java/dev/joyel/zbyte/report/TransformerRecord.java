package dev.joyel.zbyte.report;

public record TransformerRecord(int order, String name, long durationMillis, int passes, boolean changed, Metrics before, Metrics after, String error) {

    public boolean failed() {
        return error != null;
    }
}
