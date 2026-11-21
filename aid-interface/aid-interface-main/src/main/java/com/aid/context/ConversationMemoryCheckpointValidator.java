package com.aid.context;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Source validation for derived notes, never a permission or a semantic truth verdict. */
public final class ConversationMemoryCheckpointValidator {
    private ConversationMemoryCheckpointValidator() { }
    public enum NoteKind { USER_REQUIREMENT, CONFIRMED_INPUT, SOURCE_NOTE, ASSISTANT_NOTE }
    public record Citation(String sourceId, int startOffset, int endOffset, String quote) { }
    /** Models quote text; the host resolves offsets. Ambiguous quotes require an exact checked offset. */
    public static Citation resolveCitation(ConversationMemoryPreparationPlanner.Batch batch,
                                           String sourceId, String quote, Integer startOffset) {
        if (batch == null || sourceId == null || quote == null || quote.isEmpty())
            throw invalid("CONTEXT_CHECKPOINT_QUOTE_INVALID");
        ConversationMemoryPreparationPlanner.requireUnicode(quote);
        ConversationMemoryPreparationPlanner.Slice found = null;
        for (var slice : batch.slices()) {
            if (!sourceId.equals(slice.source().sourceId())) continue;
            if (found != null) throw invalid("CONTEXT_CHECKPOINT_SOURCE_COLLISION");
            found = slice;
        }
        if (found == null) throw invalid("CONTEXT_CHECKPOINT_SOURCE_UNAVAILABLE");
        int offset;
        if (startOffset == null) {
            offset = found.text().indexOf(quote);
            if (offset >= 0 && found.text().indexOf(quote, offset + 1) >= 0)
                throw invalid("CONTEXT_CHECKPOINT_QUOTE_AMBIGUOUS");
        } else {
            long relative = (long) startOffset - found.startOffset();
            if (relative < 0 || relative > found.text().length())
                throw invalid("CONTEXT_CHECKPOINT_QUOTE_INVALID");
            offset = (int) relative;
        }
        if (offset < 0 || !found.text().startsWith(quote, offset))
            throw invalid("CONTEXT_CHECKPOINT_QUOTE_INVALID");
        return new Citation(sourceId, found.startOffset() + offset,
                found.startOffset() + offset + quote.length(), quote);
    }
    public record Candidate(NoteKind kind, String text, List<Citation> citations) {
        public Candidate { citations = List.copyOf(citations); }
    }
    public record Reference(ConversationMemoryPreparationPlanner.SourceIdentity source,
                            int startOffset, int endOffset) { }
    public record Note(String noteId, NoteKind kind, String text, List<Reference> references) {
        public Note { references = List.copyOf(references); }
    }
    public record Validated(String batchDigest, String sourceSetDigest, String semanticAuthority,
                            List<Note> notes, ConversationMemoryPreparationPlanner.Progress progress,
                            String notesJson) {
        public Validated { notes = List.copyOf(notes); }
    }
    public static Validated validate(ConversationMemoryPreparationPlanner.Batch batch,
                                     String proposedBatchDigest, List<Candidate> candidates,
                                     int maxNotesBytes) {
        if (batch == null || !batch.batchDigest().equals(proposedBatchDigest)
                || candidates == null || candidates.size() > 128 || maxNotesBytes < 1)
            throw invalid("CONTEXT_CHECKPOINT_INVALID");
        // The batch comes from the host's frozen, authorized source view, never from
        // model arguments. Its cursor is the only cursor a successful result carries.
        var slices = new HashMap<String, ConversationMemoryPreparationPlanner.Slice>();
        var sourceOrder = new HashMap<String, Integer>();
        for (var slice : batch.slices()) {
            if (slices.putIfAbsent(slice.source().sourceId(), slice) != null)
                throw invalid("CONTEXT_CHECKPOINT_SOURCE_COLLISION");
            sourceOrder.put(slice.source().sourceId(), sourceOrder.size());
        }
        var coveredUsers = new HashSet<String>();
        var completeAnswers = new HashSet<String>();
        var noteIds = new HashSet<String>();
        var notes = new ArrayList<Note>();
        var wireNotes = new JSONArray();
        for (var candidate : candidates) {
            if (candidate == null || candidate.kind() == null || candidate.text() == null
                    || candidate.text().isBlank() || candidate.citations().isEmpty()
                    || candidate.citations().size() > 16
                    || candidate.text().getBytes(StandardCharsets.UTF_8).length > maxNotesBytes)
                throw invalid("CONTEXT_CHECKPOINT_NOTE_INVALID");
            ConversationMemoryPreparationPlanner.requireUnicode(candidate.text());
            var citations = new ArrayList<>(candidate.citations());
            for (var citation : citations)
                if (citation == null || citation.sourceId() == null
                        || !slices.containsKey(citation.sourceId()))
                    throw invalid("CONTEXT_CHECKPOINT_SOURCE_UNAVAILABLE");
            citations.sort(Comparator.comparingInt((Citation c) -> sourceOrder.get(c.sourceId()))
                    .thenComparingInt(Citation::startOffset).thenComparingInt(Citation::endOffset));
            var references = new ArrayList<Reference>();
            var quotes = new ArrayList<String>();
            var wireReferences = new JSONArray();
            Citation previous = null;
            for (var citation : citations) {
                var slice = slices.get(citation.sourceId());
                int start = citation.startOffset() - slice.startOffset();
                int end = citation.endOffset() - slice.startOffset();
                if (start < 0 || end <= start || end > slice.text().length()
                        || Character.isLowSurrogate(slice.text().charAt(start))
                        || end < slice.text().length() && Character.isLowSurrogate(slice.text().charAt(end))
                        || !slice.text().substring(start, end).equals(citation.quote())
                        || previous != null && previous.sourceId().equals(citation.sourceId())
                        && citation.startOffset() < previous.endOffset())
                    throw invalid("CONTEXT_CHECKPOINT_QUOTE_INVALID");
                var kind = slice.source().kind();
                if (candidate.kind() == NoteKind.USER_REQUIREMENT && kind != ConversationContextPolicy.Kind.USER
                        || candidate.kind() == NoteKind.CONFIRMED_INPUT && kind != ConversationContextPolicy.Kind.CONFIRMED_INPUT
                        || candidate.kind() == NoteKind.ASSISTANT_NOTE && kind != ConversationContextPolicy.Kind.ASSISTANT)
                    throw invalid("CONTEXT_CHECKPOINT_AUTHORITY_CHANGED");
                if (candidate.kind() == NoteKind.USER_REQUIREMENT)
                    coveredUsers.add(citation.sourceId());
                if (candidate.kind() == NoteKind.CONFIRMED_INPUT && start == 0 && end == slice.text().length())
                    completeAnswers.add(citation.sourceId());
                references.add(new Reference(slice.source(), citation.startOffset(), citation.endOffset()));
                quotes.add(citation.quote());
                JSONObject ref = new JSONObject();
                ref.put("sourceId", slice.source().sourceId());
                ref.put("sequence", slice.source().sequence());
                ref.put("sourceKind", slice.source().kind().name());
                ref.put("contentHash", slice.source().contentHash());
                ref.put("startOffset", citation.startOffset());
                ref.put("endOffset", citation.endOffset());
                wireReferences.add(ref);
                previous = citation;
            }
            if ((candidate.kind() == NoteKind.USER_REQUIREMENT || candidate.kind() == NoteKind.CONFIRMED_INPUT)
                    && !String.join("\n", quotes).equals(candidate.text()))
                throw invalid("CONTEXT_CHECKPOINT_REQUIREMENT_REWRITTEN");
            JSONObject wire = new JSONObject();
            wire.put("kind", candidate.kind().name());
            wire.put("text", candidate.text());
            wire.put("references", wireReferences);
            String noteId = sha256("conversation-memory-note/v1\n" + wire.toJSONString());
            if (!noteIds.add(noteId)) throw invalid("CONTEXT_CHECKPOINT_NOTE_DUPLICATED");
            notes.add(new Note(noteId, candidate.kind(), candidate.text(), references));
            wire.put("noteId", noteId);
            wireNotes.add(wire);
        }
        for (var slice : batch.slices()) {
            if (slice.source().kind() == ConversationContextPolicy.Kind.USER
                    && !coveredUsers.contains(slice.source().sourceId())
                    || slice.source().kind() == ConversationContextPolicy.Kind.CONFIRMED_INPUT
                    && !completeAnswers.contains(slice.source().sourceId()))
                throw invalid("CONTEXT_CHECKPOINT_REQUIRED_SOURCE_UNACCOUNTED");
        }
        JSONObject document = new JSONObject();
        document.put("schemaVersion", "conversation-memory-notes/v1");
        document.put("semanticAuthority", "DERIVED_ONLY");
        document.put("notes", wireNotes);
        String json = document.toJSONString();
        if (json.getBytes(StandardCharsets.UTF_8).length > maxNotesBytes)
            throw invalid("CONTEXT_CHECKPOINT_NOTES_TOO_LARGE");
        return new Validated(batch.batchDigest(), batch.sourceSetDigest(), "DERIVED_ONLY",
                notes, batch.next(), json);
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("CONTEXT_CHECKPOINT_DIGEST_FAILED", impossible);
        }
    }
}
