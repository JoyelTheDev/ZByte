package dev.joyel.zbyte.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RunReport {

    private final String tool = "ZByte";
    private final String mode;
    private final String input;
    private final String output;
    private final String startedAt;
    private final boolean smartRedo;
    private String status = "running";
    private String error;
    private long durationMillis;
    private long inputBytes;
    private long outputBytes;
    private boolean sandboxEnabled;
    private Metrics inputMetrics;
    private Metrics outputMetrics;
    private final List<TransformerRecord> transformers = new ArrayList<>();
    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, Integer> sandboxViolations = new LinkedHashMap<>();

    public RunReport(String mode, String input, String output, boolean smartRedo) {
        this.mode = mode;
        this.input = input;
        this.output = output;
        this.smartRedo = smartRedo;
        this.startedAt = Instant.now().toString();
    }

    public String getMode() {
        return mode;
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public boolean isSmartRedo() {
        return smartRedo;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public long getInputBytes() {
        return inputBytes;
    }

    public long getOutputBytes() {
        return outputBytes;
    }

    public boolean isSandboxEnabled() {
        return sandboxEnabled;
    }

    public Metrics getInputMetrics() {
        return inputMetrics;
    }

    public Metrics getOutputMetrics() {
        return outputMetrics;
    }

    public List<TransformerRecord> getTransformers() {
        return transformers;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public Map<String, Integer> getSandboxViolations() {
        return sandboxViolations;
    }

    public void setInputMetrics(Metrics inputMetrics) {
        this.inputMetrics = inputMetrics;
    }

    public void setOutputMetrics(Metrics outputMetrics) {
        this.outputMetrics = outputMetrics;
    }

    public void setInputBytes(long inputBytes) {
        this.inputBytes = inputBytes;
    }

    public void addTransformer(TransformerRecord record) {
        transformers.add(record);
    }

    public void addFinding(Finding finding) {
        findings.add(finding);
    }

    public void complete(Throwable failure, long durationMillis, boolean sandboxEnabled, Map<String, Integer> violations, long outputBytes) {
        this.durationMillis = durationMillis;
        this.sandboxEnabled = sandboxEnabled;
        this.outputBytes = outputBytes;
        if (violations != null) {
            sandboxViolations.putAll(violations);
        }
        if (failure == null) {
            status = "success";
        } else {
            status = "failed";
            error = describe(failure);
        }
    }

    public static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getName() : t.getClass().getName() + ": " + message;
    }

    public long totalTransformerMillis() {
        long total = 0;
        for (TransformerRecord record : transformers) {
            total += record.durationMillis();
        }
        return total;
    }

    public int changedTransformers() {
        int count = 0;
        for (TransformerRecord record : transformers) {
            if (record.changed()) {
                count++;
            }
        }
        return count;
    }
}
