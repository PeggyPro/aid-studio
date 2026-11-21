package com.aid.context;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Bounded source preparation; callers authorize sources and own the accepted progress cursor. */
public final class ConversationMemoryPreparationPlanner {
    // Source metadata and required citations also consume the preparation request.
    // A text-only budget otherwise admits thousands of one-character messages.
    private static final int MAX_SLICES_PER_BATCH = 32;
    private static final String EMPTY_PREFIX = sha256(
            "conversation-memory-covered-prefix/v1".getBytes(StandardCharsets.UTF_8));
    private ConversationMemoryPreparationPlanner() { }

    public record SourceIdentity(String sourceId, long sequence,
                                 ConversationContextPolicy.Kind kind, String contentHash) { }
    public record Slice(SourceIdentity source, int startOffset, int endOffset, String text) { }
    public record Progress(int completedSources, String completedPrefixHash, String partialSourceId,
                           String partialContentHash, int nextOffset) {
        public Progress {
            if (completedSources < 0 || completedPrefixHash == null
                    || !completedPrefixHash.matches("[a-f0-9]{64}")) throw changed();
        }
        public static Progress start() { return new Progress(0, EMPTY_PREFIX, null, null, 0); }
    }
    public record Batch(String sourceSetDigest, String batchDigest, List<Slice> slices,
                        Progress start, Progress next, boolean complete, long utf8TextBytes) {
        public Batch { slices = List.copyOf(slices); }
    }

    public static Batch nextBatch(List<ConversationContextPolicy.Source> sources,
                                  Progress progress, int textByteBudget) {
        if (sources == null || progress == null || textByteBudget < 4)
            throw new IllegalArgumentException("CONTEXT_PREPARATION_INVALID");
        var ordered = new ArrayList<>(sources);
        var ids = new HashSet<String>();
        for (var source : ordered) {
            if (source == null || !ids.add(source.sourceId()))
                throw new IllegalArgumentException("CONTEXT_SOURCE_IDENTITY_COLLISION");
            requireUnicode(source.text());
        }
        ordered.sort(Comparator.comparingLong(ConversationContextPolicy.Source::sequence)
                .thenComparing(ConversationContextPolicy.Source::sourceId));
        var identities = ordered.stream().map(source -> new SourceIdentity(source.sourceId(),
                source.sequence(), source.kind(), sha256(source.text().getBytes(StandardCharsets.UTF_8)))).toList();
        if (progress.completedSources() > ordered.size()) throw changed();
        String sourcePrefix = EMPTY_PREFIX;
        String coveredPrefix = progress.completedSources() == 0 ? EMPTY_PREFIX : null;
        for (int i = 0; i < identities.size(); i++) {
            sourcePrefix = extendPrefix(sourcePrefix, identities.get(i));
            if (i + 1 == progress.completedSources()) coveredPrefix = sourcePrefix;
        }
        if (!progress.completedPrefixHash().equals(coveredPrefix)) throw changed();
        int index = progress.completedSources();
        int offset = progress.nextOffset();
        if (progress.partialSourceId() == null) {
            if (progress.partialContentHash() != null || offset != 0) throw changed();
        } else {
            if (index >= ordered.size()
                    || !progress.partialSourceId().equals(identities.get(index).sourceId())
                    || !identities.get(index).contentHash().equals(progress.partialContentHash())
                    || offset <= 0 || offset >= ordered.get(index).text().length()
                    || Character.isLowSurrogate(ordered.get(index).text().charAt(offset))) throw changed();
        }
        String finalSourcePrefix = sourcePrefix;
        String sourceSetDigest = fingerprint("conversation-memory-sources/v1", out -> {
            out.writeInt(identities.size());
            writeText(out, finalSourcePrefix);
        });
        String completedPrefix = progress.completedPrefixHash();
        var slices = new ArrayList<Slice>();
        long used = 0;
        while (index < ordered.size()) {
            String text = ordered.get(index).text();
            if (offset == text.length()) {
                completedPrefix = extendPrefix(completedPrefix, identities.get(index++));
                offset = 0;
                continue;
            }
            if (slices.size() >= MAX_SLICES_PER_BATCH) break;
            int end = offset;
            long bytes = 0;
            while (end < text.length()) {
                int point = text.codePointAt(end);
                int width = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
                if (bytes + width > textByteBudget - used) break;
                bytes += width;
                end += Character.charCount(point);
            }
            if (end == offset) break;
            slices.add(new Slice(identities.get(index), offset, end, text.substring(offset, end)));
            used += bytes;
            offset = end;
        }
        boolean complete = index == ordered.size();
        Progress next = new Progress(index, completedPrefix, offset == 0 ? null : identities.get(index).sourceId(),
                offset == 0 ? null : identities.get(index).contentHash(), offset);
        String batchDigest = fingerprint("conversation-memory-batch/v1", out -> {
            writeText(out, sourceSetDigest);
            writeProgress(out, progress);
            out.writeInt(slices.size());
            for (var slice : slices) {
                writeIdentity(out, slice.source());
                out.writeInt(slice.startOffset());
                out.writeInt(slice.endOffset());
                writeText(out, slice.text());
            }
            writeProgress(out, next);
        });
        return new Batch(sourceSetDigest, batchDigest, slices, progress, next, complete, used);
    }

    private static IllegalArgumentException changed() {
        return new IllegalArgumentException("CONTEXT_PREPARATION_COVERAGE_CHANGED");
    }

    static void requireUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("CONTEXT_SOURCE_INVALID_UNICODE");
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("CONTEXT_SOURCE_INVALID_UNICODE");
            }
        }
    }

    private static void writeIdentity(DataOutputStream out, SourceIdentity identity) throws IOException {
        writeText(out, identity.sourceId());
        out.writeLong(identity.sequence());
        writeText(out, identity.kind().name());
        writeText(out, identity.contentHash());
    }

    private static String extendPrefix(String previous, SourceIdentity identity) {
        return fingerprint("conversation-memory-covered-source/v1", out -> {
            writeText(out, previous);
            writeIdentity(out, identity);
        });
    }

    private static void writeProgress(DataOutputStream out, Progress progress) throws IOException {
        out.writeInt(progress.completedSources());
        writeText(out, progress.completedPrefixHash());
        writeText(out, progress.partialSourceId() == null ? "" : progress.partialSourceId());
        writeText(out, progress.partialContentHash() == null ? "" : progress.partialContentHash());
        out.writeInt(progress.nextOffset());
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    @FunctionalInterface
    private interface HashInput { void write(DataOutputStream output) throws IOException; }

    private static String fingerprint(String format, HashInput input) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                writeText(out, format);
                input.write(out);
            }
            return sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("CONTEXT_PREPARATION_DIGEST_FAILED", impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("CONTEXT_PREPARATION_DIGEST_FAILED", impossible);
        }
    }
}
