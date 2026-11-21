package com.aid.assistant.source.parser;

import com.aid.assistant.error.AssistantServiceException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class SourceParserSupport {
    static final int MAX_EXTRACTED_CHARS = 5_000_000;

    private SourceParserSupport() {
    }

    static String sha256(String content) {
        return HexFormat.of().formatHex(sha256().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    static int words(String content) {
        int result = 0;
        boolean inWord = false;
        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            boolean word = Character.isLetterOrDigit(codePoint);
            if (word && !inWord) result++;
            inWord = word;
            offset += Character.charCount(codePoint);
        }
        return result;
    }

    static void requireValidUtf16(String content) {
        if (content == null || content.length() > MAX_EXTRACTED_CHARS) {
            throw invalid("SOURCE_TEXT_LIMIT_EXCEEDED", "Extracted source text exceeds the supported limit");
        }
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= content.length() || !Character.isLowSurrogate(content.charAt(++index))) {
                    throw invalid("SOURCE_TEXT_INVALID_UTF16", "Extracted source text contains invalid UTF-16");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw invalid("SOURCE_TEXT_INVALID_UTF16", "Extracted source text contains invalid UTF-16");
            }
        }
    }

    static AssistantServiceException invalid(String code, String message) {
        return new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(), code, "文档内容不可用", null);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
