package dev.joyel.zbyte.executor.sandbox;

import dev.joyel.zbyte.executor.providers.DelegatingProvider;
import dev.joyel.zbyte.executor.providers.Provider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SandboxTest {

    private SandboxPolicy previous;

    @Before
    public void install() {
        previous = SandboxPolicy.active();
        SandboxPolicy.install(new SandboxPolicy(true, false, 1000, 0, Collections.emptyList(), Collections.emptyList()));
    }

    @After
    public void restore() {
        SandboxPolicy.install(previous);
    }

    @Test
    public void deniesDangerousOwnersAndMethods() {
        SandboxPolicy policy = SandboxPolicy.active();
        assertTrue(policy.isDenied("java/lang/Runtime", "exec"));
        assertTrue(policy.isDenied("java/lang/ProcessBuilder", "<init>"));
        assertTrue(policy.isDenied("java/lang/System", "exit"));
        assertTrue(policy.isDenied("java/nio/file/Files", "delete"));
        assertTrue(policy.isDenied("java/io/File", "delete"));
        assertFalse(policy.isDenied("java/io/File", "<init>"));
        assertFalse(policy.isDenied("java/lang/String", "length"));
        assertFalse(policy.isDenied("java/lang/System", "currentTimeMillis"));
    }

    @Test
    public void allowListOverridesDeny() {
        SandboxPolicy policy = new SandboxPolicy(true, false, 0, 0, Collections.singletonList("java/io/File.delete"), Collections.emptyList());
        assertFalse(policy.isDenied("java/io/File", "delete"));
        assertTrue(policy.isDenied("java/io/File", "mkdir"));
    }

    @Test
    public void extraDenyRulesApply() {
        SandboxPolicy policy = new SandboxPolicy(true, false, 0, 0, Collections.emptyList(), Collections.singletonList("com/example/"));
        assertTrue(policy.isDenied("com/example/Evil", "run"));
    }

    @Test
    public void disabledPolicyDeniesNothing() {
        SandboxPolicy policy = new SandboxPolicy(false, false, 0, 0, Collections.emptyList(), Collections.emptyList());
        assertFalse(policy.isDenied("java/lang/Runtime", "exec"));
    }

    @Test
    public void providerBlocksDeniedInvocation() {
        Provider provider = SandboxedProvider.wrap(new DelegatingProvider());
        assertTrue(provider.canInvokeMethod("java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;", null, Collections.emptyList(), null));
        assertFalse(provider.canInvokeMethod("java/lang/String", "length", "()I", null, Collections.emptyList(), null));
        try {
            provider.invokeMethod("java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;", null, Collections.emptyList(), null);
            fail();
        } catch (SandboxViolationException expected) {
        }
    }

    @Test
    public void logOnlyModeDoesNotBlock() {
        SandboxPolicy.install(new SandboxPolicy(true, true, 0, 0, Collections.emptyList(), Collections.emptyList()));
        Provider provider = SandboxedProvider.wrap(new DelegatingProvider());
        assertFalse(provider.canInvokeMethod("java/lang/Runtime", "exec", "()V", null, Collections.emptyList(), null));
    }

    @Test
    public void meterStopsRunawayExecution() {
        SandboxPolicy.Meter meter = SandboxPolicy.active().newMeter();
        try {
            for (int i = 0; i < 5000; i++) {
                meter.tick();
            }
            fail();
        } catch (SandboxViolationException expected) {
        }
    }
}
