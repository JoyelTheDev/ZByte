package dev.joyel.zbyte.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.joyel.zbyte.executor.sandbox.SandboxPolicy;

import java.util.ArrayList;
import java.util.List;

public class SandboxConfig {

    @JsonProperty
    private boolean enabled = true;

    @JsonProperty
    private boolean logOnly = false;

    @JsonProperty
    private long maxInstructions = SandboxPolicy.DEFAULT_MAX_INSTRUCTIONS;

    @JsonProperty
    private long timeoutMillis = 0L;

    @JsonProperty
    private List<String> allow = new ArrayList<>();

    @JsonProperty
    private List<String> deny = new ArrayList<>();

    public SandboxPolicy toPolicy() {
        return new SandboxPolicy(enabled, logOnly, maxInstructions, timeoutMillis, allow, deny);
    }
}
