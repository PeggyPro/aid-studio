package com.aid.assistant.creative.service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic bounded-memory UTF-16 chunking with raw UTF-8 SHA-256. */
public final class CreativeTextChunker {
    private CreativeTextChunker() {
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(Chunk chunk);
    }

    public record Chunk(int index, int charStart, int charEnd, int wordCount,
                        String content, String contentHash) {
    }

    public record Stats(int charCount, int wordCount, int chunkCount, String contentHash) {
    }

    public static Stats scan(Reader reader, int maxChunkChars, ChunkConsumer consumer) throws IOException {
        if (reader == null || maxChunkChars < 1000 || maxChunkChars > 50000) {
            throw new IllegalArgumentException("Invalid source chunk configuration");
        }
        MessageDigest full = sha256();
        StringBuilder pending = new StringBuilder(maxChunkChars + 4096);
        char[] buffer = new char[4096];
        ScanState state = new ScanState();
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            if (read == 0) continue;
            pending.append(buffer, 0, read);
            while (pending.length() >= maxChunkChars) emit(pending, chooseCut(pending, maxChunkChars), state, full, consumer);
        }
        if (!pending.isEmpty()) emit(pending, pending.length(), state, full, consumer);
        return new Stats(state.charStart, state.totalWords, state.chunkIndex,
                HexFormat.of().formatHex(full.digest()));
    }

    private static int chooseCut(StringBuilder pending, int max) {
        int minimum = Math.max(1, max * 3 / 4);
        int newline = pending.lastIndexOf("\n", max - 1);
        int cut = newline >= minimum ? newline + 1 : max;
        if (cut > 0 && Character.isHighSurrogate(pending.charAt(cut - 1))) cut--;
        if (cut == 0) throw new IllegalArgumentException("Invalid UTF-16 source boundary");
        return cut;
    }

    private static void emit(StringBuilder pending, int cut, ScanState state,
                             MessageDigest full, ChunkConsumer consumer) {
        String content = pending.substring(0, cut);
        validateUtf16(content);
        int words = countWords(content, state);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        full.update(bytes);
        String hash = HexFormat.of().formatHex(sha256().digest(bytes));
        int end = Math.addExact(state.charStart, content.length());
        consumer.accept(new Chunk(state.chunkIndex, state.charStart, end, words, content, hash));
        state.chunkIndex++;
        state.charStart = end;
        state.totalWords += words;
        pending.delete(0, cut);
    }

    private static int countWords(String content, ScanState state) {
        int words = 0;
        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            boolean word = Character.isLetterOrDigit(codePoint);
            if (word && !state.inWord) words++;
            state.inWord = word;
            offset += Character.charCount(codePoint);
        }
        return words;
    }

    private static void validateUtf16(String content) {
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= content.length() || !Character.isLowSurrogate(content.charAt(++i))) {
                    throw new IllegalArgumentException("Source contains invalid UTF-16");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Source contains invalid UTF-16");
            }
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class ScanState {
        private int chunkIndex;
        private int charStart;
        private int totalWords;
        private boolean inWord;
    }
}
