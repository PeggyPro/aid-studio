package com.aid.assistant.source.parser;

import com.aid.assistant.source.service.SourceDocumentParser;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict UTF-8 TXT/Markdown parser. It never guesses a legacy encoding. */
@Component
public final class Utf8TextSourceDocumentParser implements SourceDocumentParser {
    public static final String CODE = "UTF8_TEXT";
    public static final String VERSION = "1.0.0";
    private static final int MAX_PART_CHARS = 250_000;
    private static final int MIN_SOFT_CUT_CHARS = 180_000;

    @Override
    public String parserCode() {
        return CODE;
    }

    @Override
    public String parserVersion() {
        return VERSION;
    }

    @Override
    public boolean supports(String extension, String mediaType, byte[] prefix) {
        if (!("txt".equals(extension) || "md".equals(extension) || "markdown".equals(extension))) return false;
        if (prefix == null) return true;
        for (byte value : prefix) if (value == 0) return false;
        return !startsWith(prefix, new byte[] {'P', 'K', 3, 4})
                && !startsWith(prefix, "%PDF-".getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public Discovery discover(byte[] immutableBytes) {
        Parsed parsed = parseAll(immutableBytes);
        return new Discovery(parsed.parts().stream()
                .map(value -> new PartDescriptor(value.partIndex(), value.partKind(), value.locatorJson()))
                .toList(), parsed.charCount());
    }

    @Override
    public List<ParsedPart> parse(byte[] immutableBytes, Set<Integer> requestedPartIndexes) {
        Parsed parsed = parseAll(immutableBytes);
        if (requestedPartIndexes == null || requestedPartIndexes.isEmpty()) return parsed.parts();
        Set<Integer> requested = new LinkedHashSet<>(requestedPartIndexes);
        if (requested.stream().anyMatch(value -> value == null || value < 0 || value >= parsed.parts().size())) {
            throw SourceParserSupport.invalid("SOURCE_PART_INDEX_INVALID", "Requested source part is invalid");
        }
        return parsed.parts().stream().filter(value -> requested.contains(value.partIndex())).toList();
    }

    private Parsed parseAll(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw SourceParserSupport.invalid("SOURCE_FILE_EMPTY", "Source file is empty");
        }
        String content = decode(bytes);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
        if (content.indexOf('\u0000') >= 0) {
            throw SourceParserSupport.invalid("SOURCE_TEXT_BINARY", "Source text contains binary data");
        }
        SourceParserSupport.requireValidUtf16(content);
        if (content.isEmpty()) throw SourceParserSupport.invalid("SOURCE_TEXT_EMPTY", "Source text is empty");

        boolean markdown = looksLikeMarkdown(content);
        List<Range> ranges = ranges(content, markdown);
        List<ParsedPart> parts = new ArrayList<>(ranges.size());
        int lineStart = 1;
        int previousEnd = 0;
        for (int index = 0; index < ranges.size(); index++) {
            Range range = ranges.get(index);
            if (range.start() != previousEnd || range.end() <= range.start()) {
                throw new IllegalStateException("Source parser produced a non-contiguous part range");
            }
            String text = content.substring(range.start(), range.end());
            int lineEnd = lineStart + countNewlines(text);
            JSONObject locator = new JSONObject();
            locator.put("format", markdown ? "MARKDOWN" : "TEXT");
            locator.put("partIndex", index);
            locator.put("charStart", range.start());
            locator.put("charEnd", range.end());
            locator.put("lineStart", lineStart);
            locator.put("lineEnd", lineEnd);
            if (range.heading() != null) locator.put("heading", range.heading());
            parts.add(new ParsedPart(index, range.heading() == null ? "TEXT_BLOCK" : "SECTION",
                    locator.toJSONString(), text, SourceParserSupport.sha256(text), text.length(),
                    SourceParserSupport.words(text)));
            lineStart = lineEnd;
            previousEnd = range.end();
        }
        if (previousEnd != content.length()) throw new IllegalStateException("Source parser omitted content");
        return new Parsed(List.copyOf(parts), content.length());
    }

    private String decode(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException invalid) {
            throw SourceParserSupport.invalid("SOURCE_TEXT_NOT_UTF8", "Source text must be strict UTF-8");
        }
    }

    private List<Range> ranges(String content, boolean markdown) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        if (markdown) {
            int lineStart = 0;
            while (lineStart < content.length()) {
                int lineEnd = content.indexOf('\n', lineStart);
                if (lineEnd < 0) lineEnd = content.length();
                if (lineStart > 0 && markdownHeading(content, lineStart, lineEnd) != null) boundaries.add(lineStart);
                lineStart = lineEnd == content.length() ? content.length() : lineEnd + 1;
            }
        }
        boundaries.add(content.length());

        List<Range> result = new ArrayList<>();
        for (int boundary = 0; boundary + 1 < boundaries.size(); boundary++) {
            int start = boundaries.get(boundary);
            int end = boundaries.get(boundary + 1);
            String heading = markdown ? firstHeading(content, start, end) : null;
            while (end - start > MAX_PART_CHARS) {
                int cut = softCut(content, start, Math.min(end, start + MAX_PART_CHARS));
                result.add(new Range(start, cut, heading));
                start = cut;
                heading = null;
            }
            if (end > start) result.add(new Range(start, end, heading));
        }
        if (result.size() > 10_000) {
            throw SourceParserSupport.invalid("SOURCE_PART_LIMIT_EXCEEDED", "Source contains too many parts");
        }
        return result;
    }

    private int softCut(String content, int start, int hardEnd) {
        int minimum = Math.min(hardEnd, start + MIN_SOFT_CUT_CHARS);
        int newline = content.lastIndexOf('\n', hardEnd - 1);
        int cut = newline >= minimum ? newline + 1 : hardEnd;
        if (cut > start && Character.isHighSurrogate(content.charAt(cut - 1))) cut--;
        if (cut <= start) throw SourceParserSupport.invalid("SOURCE_TEXT_INVALID_UTF16", "Invalid text boundary");
        return cut;
    }

    private boolean looksLikeMarkdown(String content) {
        int offset = 0;
        while (offset < content.length()) {
            int end = content.indexOf('\n', offset);
            if (end < 0) end = content.length();
            if (markdownHeading(content, offset, end) != null) return true;
            offset = end == content.length() ? content.length() : end + 1;
        }
        return false;
    }

    private String firstHeading(String content, int start, int end) {
        int lineEnd = content.indexOf('\n', start);
        if (lineEnd < 0 || lineEnd > end) lineEnd = end;
        return markdownHeading(content, start, lineEnd);
    }

    private String markdownHeading(String content, int start, int end) {
        int cursor = start;
        while (cursor < end && content.charAt(cursor) == '#') cursor++;
        int level = cursor - start;
        if (level < 1 || level > 6 || cursor >= end || content.charAt(cursor) != ' ') return null;
        String value = content.substring(cursor + 1, end).trim();
        return value.isEmpty() ? null : value.substring(0, Math.min(value.length(), 200));
    }

    private int countNewlines(String content) {
        int count = 0;
        for (int index = 0; index < content.length(); index++) if (content.charAt(index) == '\n') count++;
        return count;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (value[index] != prefix[index]) return false;
        return true;
    }

    private record Range(int start, int end, String heading) {
    }

    private record Parsed(List<ParsedPart> parts, int charCount) {
    }
}
