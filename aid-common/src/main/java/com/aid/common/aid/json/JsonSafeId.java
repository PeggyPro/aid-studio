package com.aid.common.aid.json;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将超出 JavaScript 安全整数范围的数据库编号转换为十进制字符串。 */
public final class JsonSafeId {
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9007199254740991L);

    private JsonSafeId() {
    }

    public static Object value(Object id) {
        if (!(id instanceof Byte || id instanceof Short || id instanceof Integer
                || id instanceof Long || id instanceof BigInteger)) {
            return id;
        }
        BigInteger integer = id instanceof BigInteger bigInteger
                ? bigInteger : BigInteger.valueOf(((Number) id).longValue());
        return integer.abs().compareTo(MAX_SAFE_INTEGER) > 0 ? integer.toString() : id;
    }

    public static Map<String, Object> fields(Map<String, Object> source, String... fieldNames) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        for (String fieldName : fieldNames) {
            if (result.containsKey(fieldName)) {
                result.put(fieldName, value(result.get(fieldName)));
            }
        }
        return result;
    }
}
