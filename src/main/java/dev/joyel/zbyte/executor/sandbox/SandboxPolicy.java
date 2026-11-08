package dev.joyel.zbyte.executor.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SandboxPolicy {

    public static final long DEFAULT_MAX_INSTRUCTIONS = 500_000_000L;

    private static final Logger logger = LoggerFactory.getLogger(SandboxPolicy.class);

    private static final List<String> BUILT_IN_DENY = Collections.unmodifiableList(Arrays.asList(
            "java/lang/Runtime",
            "java/lang/ProcessBuilder",
            "java/lang/Process",
            "java/lang/ProcessHandle",
            "java/lang/System.exit",
            "java/lang/System.load",
            "java/lang/System.loadLibrary",
            "java/lang/System.setSecurityManager",
            "java/lang/System.setProperty",
            "java/lang/System.setProperties",
            "java/lang/System.clearProperty",
            "java/lang/System.setOut",
            "java/lang/System.setErr",
            "java/lang/System.setIn",
            "java/lang/System.getenv",
            "java/lang/Thread.stop",
            "java/lang/Thread.suspend",
            "java/lang/ClassLoader.defineClass",
            "java/lang/invoke/MethodHandles$Lookup.defineClass",
            "java/net/URLClassLoader",
            "java/net/Socket",
            "java/net/ServerSocket",
            "java/net/DatagramSocket",
            "java/net/InetAddress",
            "java/net/HttpURLConnection",
            "java/net/URLConnection",
            "java/net/URL.openConnection",
            "java/net/URL.getContent",
            "java/nio/file/",
            "java/nio/channels/",
            "java/io/FileOutputStream",
            "java/io/FileWriter",
            "java/io/RandomAccessFile",
            "java/io/File.delete",
            "java/io/File.deleteOnExit",
            "java/io/File.createNewFile",
            "java/io/File.createTempFile",
            "java/io/File.mkdir",
            "java/io/File.mkdirs",
            "java/io/File.renameTo",
            "java/io/File.setReadOnly",
            "java/io/File.setReadable",
            "java/io/File.setWritable",
            "java/io/File.setExecutable",
            "java/io/File.setLastModified",
            "java/sql/",
            "javax/script/",
            "javax/naming/",
            "javax/management/",
            "java/rmi/",
            "java/awt/Desktop",
            "java/awt/Robot",
            "sun/misc/Unsafe",
            "sun/reflect/",
            "jdk/internal/"
    ));

    private static volatile SandboxPolicy active = new SandboxPolicy(true, false, DEFAULT_MAX_INSTRUCTIONS, 0L, Collections.emptyList(), Collections.emptyList());

    private static final Map<String, AtomicInteger> violations = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final boolean logOnly;
    private final long maxInstructions;
    private final long timeoutMillis;
    private final List<String> allow;
    private final List<String> deny;

    public SandboxPolicy(boolean enabled, boolean logOnly, long maxInstructions, long timeoutMillis, List<String> allow, List<String> extraDeny) {
        this.enabled = enabled;
        this.logOnly = logOnly;
        this.maxInstructions = maxInstructions;
        this.timeoutMillis = timeoutMillis;
        this.allow = new ArrayList<>(allow == null ? Collections.<String>emptyList() : allow);
        List<String> merged = new ArrayList<>(BUILT_IN_DENY);
        if (extraDeny != null) {
            merged.addAll(extraDeny);
        }
        this.deny = merged;
    }

    public static SandboxPolicy active() {
        return active;
    }

    public static void install(SandboxPolicy policy) {
        active = policy == null ? new SandboxPolicy(false, false, 0L, 0L, Collections.emptyList(), Collections.emptyList()) : policy;
        violations.clear();
        if (active.enabled) {
            logger.info("Sandbox enabled ({}, max instructions {}, timeout {} ms)", active.logOnly ? "log only" : "enforcing", active.maxInstructions, active.timeoutMillis);
        } else {
            logger.warn("Sandbox is disabled. Obfuscated code may perform dangerous host operations");
        }
    }

    public static List<String> builtInDeny() {
        return BUILT_IN_DENY;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isLogOnly() {
        return logOnly;
    }

    public long getMaxInstructions() {
        return maxInstructions;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public boolean isDenied(String owner, String name) {
        if (!enabled || owner == null || owner.isEmpty() || owner.charAt(0) == '[') {
            return false;
        }
        if (matches(allow, owner, name)) {
            return false;
        }
        return matches(deny, owner, name);
    }

    public boolean blocks(String owner, String name) {
        return !logOnly && isDenied(owner, name);
    }

    public void record(String kind, String owner, String name) {
        String key = kind + " " + owner + "." + name;
        AtomicInteger counter = violations.computeIfAbsent(key, k -> new AtomicInteger());
        if (counter.incrementAndGet() == 1) {
            logger.warn("Sandbox {} {}", logOnly ? "observed" : "blocked", key);
        }
    }

    public static Map<String, Integer> violationSummary() {
        Map<String, Integer> copy = new TreeMap<>();
        violations.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }

    public static void logSummary() {
        Map<String, Integer> summary = violationSummary();
        if (summary.isEmpty()) {
            return;
        }
        logger.warn("Sandbox intercepted {} distinct operation(s):", summary.size());
        summary.forEach((k, v) -> logger.warn("\t{} x{}", k, v));
    }

    public Meter newMeter() {
        return new Meter(enabled, maxInstructions, timeoutMillis);
    }

    private static boolean matches(List<String> rules, String owner, String name) {
        for (String rule : rules) {
            if (rule.endsWith("/")) {
                if (owner.startsWith(rule)) {
                    return true;
                }
                continue;
            }
            int dot = rule.indexOf('.');
            if (dot < 0) {
                if (owner.equals(rule)) {
                    return true;
                }
                continue;
            }
            if (!owner.equals(rule.substring(0, dot))) {
                continue;
            }
            String method = rule.substring(dot + 1);
            if (method.equals("*") || method.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static final class Meter {
        private final boolean enabled;
        private final long maxInstructions;
        private final long deadlineNanos;
        private final AtomicLong count = new AtomicLong();

        Meter(boolean enabled, long maxInstructions, long timeoutMillis) {
            this.enabled = enabled;
            this.maxInstructions = maxInstructions;
            this.deadlineNanos = timeoutMillis > 0 ? System.nanoTime() + timeoutMillis * 1_000_000L : 0L;
        }

        public void tick() {
            if (!enabled) {
                return;
            }
            long current = count.incrementAndGet();
            if (maxInstructions > 0 && current > maxInstructions) {
                throw new SandboxViolationException("Instruction limit of " + maxInstructions + " exceeded");
            }
            if (deadlineNanos != 0L && (current & 4095L) == 0L && System.nanoTime() - deadlineNanos > 0L) {
                throw new SandboxViolationException("Execution time limit exceeded");
            }
        }

        public long executed() {
            return count.get();
        }
    }
}
