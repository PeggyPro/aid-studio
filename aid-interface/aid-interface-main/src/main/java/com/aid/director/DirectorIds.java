package com.aid.director;

import com.alibaba.fastjson2.JSON;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 随机凭证与不可变内容标识。 */
public final class DirectorIds {
    private static final SecureRandom RANDOM = new SecureRandom();
    private DirectorIds() { }

    public static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String text) {
        if (text == null) throw DirectorException.invalid();
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String requestHash(Object value) {
        Object json = JSON.parse(JSON.toJSONString(value));
        return hash(JSON.toJSONBytes(sorted(json)));
    }

    private static Object sorted(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            var result = new java.util.TreeMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), sorted(item)));
            return result;
        }
        if (value instanceof java.util.List<?> list) return list.stream().map(DirectorIds::sorted).toList();
        return value;
    }

    public static String key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,64}")) throw DirectorException.invalid();
        return value;
    }

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw DirectorException.invalid();
            long id = Long.parseLong(value);
            if (id <= 0) throw DirectorException.invalid();
            return id;
        } catch (NumberFormatException e) { throw DirectorException.invalid(); }
    }
}
