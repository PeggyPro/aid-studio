package com.aid.media.provider;

import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Removes unreachable root definitions from a provider copy, never from the canonical contract. */
final class NativeFunctionSchemaReferences
{
    private static final Set<String> SCOPED_REFERENCES = Set.of(
            "$id", "$anchor", "$dynamicAnchor", "$dynamicRef", "$recursiveRef", "discriminator");
    private NativeFunctionSchemaReferences() { }

    static void prune(JSONObject schema)
    {
        Set<Reference> reachable = new HashSet<>();
        ArrayDeque<Reference> pending = new ArrayDeque<>();
        if (!collect(schema, true, pending)) return;
        while (!pending.isEmpty())
        {
            Reference reference = pending.removeFirst();
            if (!reachable.add(reference)) continue;
            Object container = schema.get(reference.container());
            if (!(container instanceof JSONObject definitions) || !definitions.containsKey(reference.name())) return;
            Object definition = definitions.get(reference.name());
            if (!(definition instanceof JSONObject || definition instanceof Boolean)
                    || !collect(definition, false, pending)) return;
        }
        for (String name : List.of("$defs", "definitions"))
        {
            if (!(schema.get(name) instanceof JSONObject definitions)) continue;
            definitions.keySet().removeIf(key -> !reachable.contains(new Reference(name, key)));
            if (definitions.isEmpty()) schema.remove(name);
        }
    }

    /** Unknown reference bases, anchors and nested definition scopes retain the complete schema. */
    private static boolean collect(Object node, boolean root, ArrayDeque<Reference> references)
    {
        if (node instanceof Map<?, ?> object)
        {
            for (Map.Entry<?, ?> entry : object.entrySet())
            {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (SCOPED_REFERENCES.contains(key)) return false;
                if ("$defs".equals(key) || "definitions".equals(key))
                {
                    if (!root) return false;
                    continue;
                }
                if ("$ref".equals(key) && value instanceof String ref)
                {
                    String container = ref.startsWith("#/$defs/") ? "$defs"
                            : ref.startsWith("#/definitions/") ? "definitions" : null;
                    if (container == null || ref.contains("%")) return false;
                    String token = ref.substring(container.length() + 3).split("/", 2)[0];
                    if (token.matches(".*~(?:[^01]|$).*")) return false;
                    references.add(new Reference(container, token.replace("~1", "/").replace("~0", "~")));
                }
                else if (!collect(value, false, references)) return false;
            }
        }
        else if (node instanceof Collection<?> values)
            for (Object value : values) if (!collect(value, false, references)) return false;
        return true;
    }

    private record Reference(String container, String name) { }
}
