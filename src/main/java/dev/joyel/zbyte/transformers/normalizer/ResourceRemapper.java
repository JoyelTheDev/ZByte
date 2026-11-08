package dev.joyel.zbyte.transformers.normalizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public final class ResourceRemapper {

    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final String SERVICES = "META-INF/services/";
    private static final String[] CLASS_ATTRIBUTES = {"Main-Class", "Premain-Class", "Agent-Class", "Launcher-Agent-Class"};

    private ResourceRemapper() {
    }

    public static int apply(Map<String, byte[]> resources, UnaryOperator<String> mapper) {
        int updated = 0;
        for (String name : new ArrayList<>(resources.keySet())) {
            if (name.equalsIgnoreCase(MANIFEST)) {
                if (remapManifest(resources, name, mapper)) {
                    updated++;
                }
            } else if (name.startsWith(SERVICES) && name.length() > SERVICES.length()) {
                if (remapService(resources, name, mapper)) {
                    updated++;
                }
            }
        }
        return updated;
    }

    private static boolean remapManifest(Map<String, byte[]> resources, String name, UnaryOperator<String> mapper) {
        Manifest manifest;
        try {
            manifest = new Manifest(new ByteArrayInputStream(resources.get(name)));
        } catch (IOException e) {
            return false;
        }
        Attributes attributes = manifest.getMainAttributes();
        boolean changed = false;
        for (String key : CLASS_ATTRIBUTES) {
            String value = attributes.getValue(key);
            if (value == null) {
                continue;
            }
            String mapped = mapDotted(value.trim(), mapper);
            if (!mapped.equals(value.trim())) {
                attributes.putValue(key, mapped);
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            manifest.write(out);
        } catch (IOException e) {
            return false;
        }
        resources.put(name, out.toByteArray());
        return true;
    }

    private static boolean remapService(Map<String, byte[]> resources, String name, UnaryOperator<String> mapper) {
        String serviceName = name.substring(SERVICES.length());
        String newServiceName = mapDotted(serviceName, mapper);
        String[] lines = new String(resources.get(name), StandardCharsets.UTF_8).split("\n", -1);
        boolean changed = !newServiceName.equals(serviceName);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int comment = line.indexOf('#');
            String code = comment >= 0 ? line.substring(0, comment) : line;
            String token = code.trim();
            if (token.isEmpty()) {
                continue;
            }
            String mapped = mapDotted(token, mapper);
            if (!mapped.equals(token)) {
                lines[i] = code.replace(token, mapped) + (comment >= 0 ? line.substring(comment) : "");
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        byte[] data = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
        resources.remove(name);
        resources.put(SERVICES + newServiceName, data);
        return true;
    }

    private static String mapDotted(String dotted, UnaryOperator<String> mapper) {
        String mapped = mapper.apply(dotted.replace('.', '/'));
        return mapped == null ? dotted : mapped.replace('/', '.');
    }
}
