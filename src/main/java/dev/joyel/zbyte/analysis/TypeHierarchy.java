package dev.joyel.zbyte.analysis;

import java.util.Set;

public interface TypeHierarchy {
    boolean isAssignableFrom(String parent, String child);

    Set<String> getAssignableSupertypes(String child);

    String getSuperName(String name);

    boolean isInterface(String name);
}
