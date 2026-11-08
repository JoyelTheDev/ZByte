package dev.joyel.zbyte.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import dev.joyel.zbyte.transformers.DelegatingTransformer;
import dev.joyel.zbyte.transformers.Transformer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TransformerConfigDeserializer extends ValueDeserializer<TransformerConfig> {

    private static final String[] SHORT_NAME_PACKAGES = {
            "universal.",
            "general.",
            "special."
            "general.peephole.",
            "general.removers.",
            "normalizer.",
            "zelix."
            
    };

    private final Logger logger;
    private final ObjectMapper updater = JsonMapper.builder().build();

    public TransformerConfigDeserializer(Logger logger) {
        this.logger = logger;
    }

    @Override
    public TransformerConfig deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(p);

        if (node.isObject()) {
            List<TransformerConfig> configs = new ArrayList<>();

            for (Map.Entry<String, JsonNode> field : node.properties()) {
                TransformerConfig config = getById(p, ctxt, field.getKey());

                configs.add(updater.readerForUpdating(config).readValue(field.getValue()));
            }

            if (configs.size() == 0) {
                throw new IllegalArgumentException("didn't see that coming " + node);
            } else if (configs.size() == 1) {
                return configs.get(0);
            } else {
                DelegatingTransformer.Config config = new DelegatingTransformer.Config();
                config.setConfigs(configs);
                return config;
            }
        } else if (node.isTextual()) {
            return getById(p, ctxt, node.asText());
        } else {
            throw ctxt.wrongTokenException(p, String.class, JsonToken.VALUE_STRING, null);
        }
    }

    private TransformerConfig getById(JsonParser p, DeserializationContext ctxt, String id) {
        Class<?> clazz = null;
        try {
            clazz = Class.forName("dev.joyel.zbyte.transformers." + id);
        } catch (ClassNotFoundException ignored) {
        }
        if (clazz == null) {
            try {
                clazz = Class.forName(id);
            } catch (ClassNotFoundException ignored) {
            }
        }
        for (int i = 0; clazz == null && i < SHORT_NAME_PACKAGES.length; i++) {
            try {
                clazz = Class.forName("dev.joyel.zbyte.transformers." + SHORT_NAME_PACKAGES[i] + id);
            } catch (ClassNotFoundException ignored) {
            }
        }

        if (clazz == DelegatingTransformer.class) {
            throw ctxt.weirdStringException(id, Transformer.class, "Cannot explicitly request DelegatingTransformer");
        }

        if (clazz != null) {
            if (Transformer.class.isAssignableFrom(clazz)) {
                return TransformerConfig.configFor(clazz.asSubclass(Transformer.class));
            } else {
                throw ctxt.weirdStringException(id, Transformer.class, "Class does not extend dev.joyel.zbyte.transformers.Transformer");
            }
        } else {
            throw ctxt.weirdStringException(id, Transformer.class, "Could not locate specified transformer");
        }
    }
}
