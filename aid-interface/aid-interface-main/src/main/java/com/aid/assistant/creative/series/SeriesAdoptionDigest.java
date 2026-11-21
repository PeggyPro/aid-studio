package com.aid.assistant.creative.series;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON and SHA-256 used by project baselines, capabilities and commands. */
public final class SeriesAdoptionDigest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SeriesAdoptionDigest() {
    }

    public static String sha256Canonical(Object value) {
        return DigestUtil.sha256Hex(canonicalJson(value));
    }

    public static String canonicalJson(Object value) {
        try {
            return MAPPER.writeValueAsString(canonical(MAPPER.valueToTree(value)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot canonicalize series adoption payload", error);
        }
    }

    public static String sha256Text(String value) {
        return DigestUtil.sha256Hex(value == null ? "" : value);
    }

    private static JsonNode canonical(JsonNode source) {
        if (source == null || source.isNull() || source.isValueNode()) {
            return source;
        }
        if (source.isArray()) {
            ArrayNode target = MAPPER.createArrayNode();
            source.forEach(item -> target.add(canonical(item)));
            return target;
        }
        ObjectNode target = MAPPER.createObjectNode();
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        fields.forEachRemaining(field -> sorted.put(field.getKey(), field.getValue()));
        sorted.forEach((name, value) -> target.set(name, canonical(value)));
        return target;
    }
}
