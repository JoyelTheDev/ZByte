package dev.joyel.zbyte.executor.sandbox;

import dev.joyel.zbyte.executor.exceptions.ExecutionException;

public class SandboxViolationException extends ExecutionException {

    public SandboxViolationException(String msg) {
        super(msg);
    }
}
