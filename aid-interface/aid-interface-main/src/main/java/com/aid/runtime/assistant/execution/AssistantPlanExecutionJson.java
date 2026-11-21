package com.aid.runtime.assistant.execution;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable JSON canonicalization for digests and immutable operation receipts. */
final class AssistantPlanExecutionJson
{
    private AssistantPlanExecutionJson()
    {
    }

    static String canonical(Object value)
    {
        return JSON.toJSONString(sort(value));
    }

    /** Canonical form for Runtime Tool arguments whose explicit null fields carry operation semantics. */
    static String canonicalExplicitNulls(Object value)
    {
        return JSON.toJSONString(sort(value), JSONWriter.Feature.WriteMapNullValue);
    }

    private static Object sort(Object value)
    {
        if (value instanceof JSONObject object)
        {
            List<String> keys = new ArrayList<>(object.keySet());
            Collections.sort(keys);
            Map<String, Object> sorted = new LinkedHashMap<>();
            for (String key : keys) sorted.put(key, sort(object.get(key)));
            return sorted;
        }
        if (value instanceof Map<?, ?> map)
        {
            List<String> keys = map.keySet().stream().map(String::valueOf).sorted().toList();
            Map<String, Object> sorted = new LinkedHashMap<>();
            for (String key : keys) sorted.put(key, sort(map.get(key)));
            return sorted;
        }
        if (value instanceof JSONArray array)
        {
            List<Object> sorted = new ArrayList<>(array.size());
            for (Object item : array) sorted.add(sort(item));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable)
        {
            List<Object> sorted = new ArrayList<>();
            for (Object item : iterable) sorted.add(sort(item));
            return sorted;
        }
        return value;
    }
}
