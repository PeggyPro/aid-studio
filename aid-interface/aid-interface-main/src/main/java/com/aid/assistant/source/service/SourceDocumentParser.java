package com.aid.assistant.source.service;

import java.util.List;
import java.util.Set;

/**
 * Deterministic parser for a server-owned immutable source blob.
 *
 * <p>Discovery and parsing run outside database transactions. A caller must bind the returned
 * parser code/version and every descriptor to the import fence before it persists any result.</p>
 */
public interface SourceDocumentParser {
    String parserCode();

    String parserVersion();

    boolean supports(String extension, String mediaType, byte[] prefix);

    Discovery discover(byte[] immutableBytes);

    List<ParsedPart> parse(byte[] immutableBytes, Set<Integer> requestedPartIndexes);

    record Discovery(List<PartDescriptor> parts, int extractedCharCount) {
        public Discovery {
            parts = List.copyOf(parts);
        }
    }

    record PartDescriptor(int partIndex, String partKind, String locatorJson) {
    }

    record ParsedPart(int partIndex, String partKind, String locatorJson,
                      String content, String contentHash, int charCount, int wordCount) {
    }
}
