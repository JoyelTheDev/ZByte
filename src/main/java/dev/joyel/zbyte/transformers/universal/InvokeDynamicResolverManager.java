package dev.joyel.zbyte.transformers.universal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class InvokeDynamicResolverManager {

    private static final Comparator<InvokeDynamicResolver> ORDER = Comparator
            .comparingInt(InvokeDynamicResolver::getPriority)
            .thenComparing(resolver -> resolver.getClass().getName());

    private static final Map<String, Class<? extends InvokeDynamicResolver>> REGISTERED = new LinkedHashMap<>();

    static {
        register(dev.joyel.zbyte.transformers.zkm.ZkmInvokeDynamicResolver.class);
    }

    private InvokeDynamicResolverManager() {
    }

    public static synchronized void register(Class<? extends InvokeDynamicResolver> type) {
        REGISTERED.put(type.getName(), type);
    }

    public static synchronized void unregister(Class<? extends InvokeDynamicResolver> type) {
        REGISTERED.remove(type.getName());
    }

    public static synchronized List<InvokeDynamicResolver> createResolvers() {
        List<InvokeDynamicResolver> resolvers = new ArrayList<>();
        for (Class<? extends InvokeDynamicResolver> type : REGISTERED.values()) {
            try {
                resolvers.add(type.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create resolver " + type.getName(), e);
            }
        }
        for (InvokeDynamicResolver discovered : ServiceLoader.load(InvokeDynamicResolver.class)) {
            if (!REGISTERED.containsKey(discovered.getClass().getName())) {
                resolvers.add(discovered);
            }
        }
        return sort(resolvers);
    }

    public static List<InvokeDynamicResolver> sort(List<? extends InvokeDynamicResolver> resolvers) {
        List<InvokeDynamicResolver> sorted = new ArrayList<>(resolvers);
        sorted.sort(ORDER);
        return sorted;
    }
}
