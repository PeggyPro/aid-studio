package com.aid.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded process-local measurements. Never invokes a model or caches an authorization decision. */
public final class TextContentMetrics {
    private static final int MAX_CHARS = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final Map<String, Measurement> CACHE = new LinkedHashMap<>(128, .75f, true);
    private static int retainedChars;
    private TextContentMetrics() { }

    public record Measurement(int utf8Bytes, long quarterTokens, String sha256) { }

    public static Measurement measure(String text) {
        if (text == null) text = "";
        // A single oversized resource must not evict the reusable working set.
        if (text.length() > MAX_CHARS) return calculate(text);
        synchronized (CACHE) {
            Measurement found = CACHE.get(text);
            if (found != null) return found;
            Measurement measured = calculate(text);
            while (!CACHE.isEmpty() && (CACHE.size() >= MAX_ENTRIES
                    || retainedChars + text.length() > MAX_CHARS)) {
                String oldest = CACHE.keySet().iterator().next();
                retainedChars -= oldest.length(); CACHE.remove(oldest);
            }
            CACHE.put(text, measured); retainedChars += text.length();
            return measured;
        }
    }

    private static Measurement calculate(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long units = 0; int asciiRun = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); offset += Character.charCount(cp);
            if (cp <= 0x7f && Character.isLetterOrDigit(cp)) { asciiRun++; units++; continue; }
            if (asciiRun > 0) { units += 1L + (asciiRun >= 32 ? asciiRun : 0L); asciiRun = 0; }
            if (cp <= 0x7f && Character.isWhitespace(cp)) continue;
            units += cp <= 0x7f ? 4L : Character.isSupplementaryCodePoint(cp) ? 8L : 6L;
        }
        if (asciiRun > 0) units += 1L + (asciiRun >= 32 ? asciiRun : 0L);
        try {
            return new Measurement(bytes.length, units,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
