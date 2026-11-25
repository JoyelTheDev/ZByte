package dev.joyel.zbyte.transformers.universal;

import dev.joyel.zbyte.config.TransformerConfig;
import dev.joyel.zbyte.transformers.Transformer;
import java.util.List;
import java.util.Locale;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

@TransformerConfig.ConfigOptions(configClass = AntiTamperTransformer.Config.class)
public class AntiTamperTransformer extends Transformer<AntiTamperTransformer.Config> {

    private static final List<String> DEBUG_MARKERS = List.of("jdwp", "-xdebug", "-agentlib", "-javaagent", "-xrunjdwp", "-xnoagent");

    @Override
    public boolean transform() throws Throwable {
        int methods = 0;
        int calls = 0;
        for (ClassNode classNode : classNodes()) {
            for (MethodNode method : classNode.methods) {
                int count = clean(method);
                if (count > 0) {
                    methods++;
                    calls += count;
                }
            }
        }
        if (calls > 0) {
            logger.info("Neutralized {} anti-tamper calls in {} methods", calls, methods);
        }
        return calls > 0;
    }

    private int clean(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return 0;
        }
        boolean debugArguments = false;
        boolean debugMarker = false;
        boolean resourceAccess = false;
        boolean checksum = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                String lower = text.toLowerCase(Locale.ROOT);
                for (String marker : DEBUG_MARKERS) {
                    if (lower.contains(marker)) {
                        debugMarker = true;
                    }
                }
            } else if (insn instanceof MethodInsnNode call) {
                debugArguments |= isInputArguments(call);
                resourceAccess |= isResourceAccess(call);
                checksum |= isChecksum(call);
            }
        }
        boolean debugGuard = debugArguments && debugMarker;
        boolean integrityGuard = resourceAccess && checksum;
        if (!debugGuard && !integrityGuard) {
            return 0;
        }
        Config config = getConfig();
        int changes = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            if (debugGuard && config.isDebugChecks() && isInputArguments(call)) {
                InsnList replacement = new InsnList();
                replacement.add(new InsnNode(POP));
                replacement.add(new MethodInsnNode(INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false));
                method.instructions.insert(call, replacement);
                method.instructions.remove(call);
                changes++;
            } else if (config.isKillSwitches() && isSystemExit(call)) {
                method.instructions.insert(call, new InsnNode(POP));
                method.instructions.remove(call);
                changes++;
            } else if (config.isKillSwitches() && isRuntimeExit(call)) {
                method.instructions.insert(call, new InsnNode(POP2));
                method.instructions.remove(call);
                changes++;
            }
        }
        return changes;
    }

    private static boolean isInputArguments(MethodInsnNode call) {
        return call.owner.equals("java/lang/management/RuntimeMXBean")
                && call.name.equals("getInputArguments")
                && call.desc.equals("()Ljava/util/List;");
    }

    private static boolean isSystemExit(MethodInsnNode call) {
        return call.getOpcode() == INVOKESTATIC && call.owner.equals("java/lang/System") && call.name.equals("exit") && call.desc.equals("(I)V");
    }

    private static boolean isRuntimeExit(MethodInsnNode call) {
        return call.getOpcode() == INVOKEVIRTUAL && call.owner.equals("java/lang/Runtime")
                && (call.name.equals("exit") || call.name.equals("halt")) && call.desc.equals("(I)V");
    }

    private static boolean isResourceAccess(MethodInsnNode call) {
        return call.name.equals("getResourceAsStream")
                || call.name.equals("getCodeSource")
                || call.name.equals("getProtectionDomain")
                || call.owner.equals("java/util/jar/JarFile")
                || call.owner.equals("java/util/jar/JarInputStream");
    }

    private static boolean isChecksum(MethodInsnNode call) {
        return call.owner.equals("java/security/MessageDigest")
                || call.owner.equals("java/util/zip/CRC32")
                || call.owner.equals("java/util/zip/Adler32")
                || call.owner.equals("java/util/zip/Checksum");
    }

    public static class Config extends TransformerConfig {

        private boolean killSwitches = true;
        private boolean debugChecks = true;

        public Config() {
            super(AntiTamperTransformer.class);
        }

        public boolean isKillSwitches() {
            return killSwitches;
        }

        public void setKillSwitches(boolean killSwitches) {
            this.killSwitches = killSwitches;
        }

        public boolean isDebugChecks() {
            return debugChecks;
        }

        public void setDebugChecks(boolean debugChecks) {
            this.debugChecks = debugChecks;
        }
    }
}
