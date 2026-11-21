package com.aid.assistant.creative.service;

import cn.hutool.crypto.SecureUtil;

import java.nio.charset.StandardCharsets;

/** Produces exact UTF-16-safe slices with a bounded raw UTF-8 payload. */
public final class CreativeTextSegmenter {
    public static final int DEFAULT_MAX_UTF8_BYTES = 8192;
    public static final int MIN_MAX_UTF8_BYTES = 256;
    public static final int MAX_MAX_UTF8_BYTES = 8192;

    private CreativeTextSegmenter() {
    }

    public static Slice slice(String value, int charOffset, Integer requestedMaxUtf8Bytes) {
        if (value == null || !wellFormedUtf16(value) || charOffset < 0 || charOffset >= value.length()
                || charOffset > 0 && Character.isLowSurrogate(value.charAt(charOffset))) {
            throw new IllegalArgumentException("Invalid creative segment cursor");
        }
        int maximum = requestedMaxUtf8Bytes == null ? DEFAULT_MAX_UTF8_BYTES : requestedMaxUtf8Bytes;
        if (maximum < MIN_MAX_UTF8_BYTES || maximum > MAX_MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("Invalid creative segment byte budget");
        }
        int end = charOffset;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int codePointBytes = utf8Bytes(codePoint);
            if (bytes + codePointBytes > maximum) break;
            bytes += codePointBytes;
            end += Character.charCount(codePoint);
        }
        if (end == charOffset) throw new IllegalArgumentException("Creative segment byte budget is too small");
        String content = value.substring(charOffset, end);
        int actualBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (actualBytes != bytes) throw new IllegalStateException("Creative segment UTF-8 accounting failed");
        return new Slice(charOffset, end, actualBytes, SecureUtil.sha256(content), content);
    }

    public static boolean wellFormedUtf16(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)) return false;
            if (!Character.isHighSurrogate(current)) continue;
            if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
        }
        return true;
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }

    public record Slice(int charStart, int charEnd, int utf8ByteCount,
                        String contentHash, String content) {
    }
}
