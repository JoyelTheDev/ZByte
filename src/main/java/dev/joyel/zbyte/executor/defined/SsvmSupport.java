package dev.joyel.zbyte.executor.defined;

import dev.joyel.zbyte.executor.providers.DelegatingProvider;
import dev.joyel.zbyte.executor.providers.Provider;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class SsvmSupport {

    public static final String PROPERTY = "zbyte.ssvm";

    private static final String PROVIDER_CLASS = "dev.joyel.zbyte.executor.ssvm.SsvmMethodProvider";
    private static final Logger logger = LoggerFactory.getLogger(SsvmSupport.class);
    private static volatile boolean warned;

    private SsvmSupport() {
    }

    public static boolean requested() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static DelegatingProvider register(DelegatingProvider target, Map<String, ClassNode> classes) {
        if (!requested()) {
            return target;
        }
        try {
            Class<?> type = Class.forName(PROVIDER_CLASS);
            Provider provider = (Provider) type.getConstructor(Map.class).newInstance(classes);
            target.register(provider);
        } catch (ClassNotFoundException | LinkageError e) {
            warnOnce("SSVM requested but not on the classpath; build with -Pssvm");
        } catch (ReflectiveOperationException e) {
            warnOnce("SSVM provider could not be created: " + e);
        }
        return target;
    }

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            logger.warn(message);
        }
    }
}
