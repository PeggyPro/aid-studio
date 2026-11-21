package com.aid.assistant.source.service;

import com.aid.assistant.error.AssistantServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Selects exactly one parser from server-detected format evidence. */
@Component
public final class SourceDocumentParserRegistry {
    private final List<SourceDocumentParser> parsers;

    public SourceDocumentParserRegistry(List<SourceDocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public SourceDocumentParser require(String originalName, String mediaType, byte[] bytes) {
        String extension = extension(originalName);
        byte[] prefix = prefix(bytes, 16);
        List<SourceDocumentParser> matching = parsers.stream()
                .filter(parser -> parser.supports(extension, mediaType, prefix))
                .toList();
        if (matching.size() != 1) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_FORMAT_UNSUPPORTED",
                    "Source format is unsupported or ambiguous", extension);
        }
        return matching.get(0);
    }

    private String extension(String name) {
        if (name == null) return "";
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String leaf = name.substring(separator + 1);
        int dot = leaf.lastIndexOf('.');
        return dot < 0 || dot == leaf.length() - 1 ? "" : leaf.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private byte[] prefix(byte[] bytes, int limit) {
        if (bytes == null || bytes.length == 0) return new byte[0];
        return java.util.Arrays.copyOf(bytes, Math.min(bytes.length, limit));
    }

    private boolean startsWith(byte[] bytes, byte[] expected) {
        if (bytes.length < expected.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (bytes[index] != expected[index]) return false;
        }
        return true;
    }

    private AssistantServiceException failure(HttpStatus status, String code, String message, Object details) {
        return new AssistantServiceException(status.value(), code, message, details);
    }
}
