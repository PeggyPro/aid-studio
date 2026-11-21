package com.aid.context;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** Shared preparation lifecycle. Adapters must freshly authorize the complete source view on each call. */
@Service
public class ConversationMemoryPreparationService {
    private final ConversationMemoryBatchStore batches;
    private final ConversationMemoryCheckpointStore checkpoints;

    public ConversationMemoryPreparationService(ConversationMemoryBatchStore batches,
                                                ConversationMemoryCheckpointStore checkpoints) {
        this.batches = Objects.requireNonNull(batches);
        this.checkpoints = Objects.requireNonNull(checkpoints);
    }

    public record Preparation(ConversationMemoryCheckpointStore.State checkpoint,
                              ConversationMemoryBatchStore.Stored batch) { }

    public ConversationMemoryCheckpointStore.State checkpoint(ConversationContextMemoryStore.Key contextScope) {
        return checkpoints.load(contextScope);
    }

    public boolean saveReadyContext(ConversationContextMemoryStore.Key contextScope, long sourceRevision, JSONObject ready) {
        return checkpoints.saveReadyContext(contextScope, sourceRevision, ready);
    }

    public JSONObject loadReadyContext(ConversationContextMemoryStore.Key contextScope, long sourceRevision) {
        return checkpoints.loadReadyContext(contextScope, sourceRevision);
    }

    /** Versioned derived projection. Original message/answer records remain available through their source IDs. */
    public JSONObject readyContext(ConversationContextMemoryStore.Key contextScope,
                                  List<ConversationContextPolicy.Source> authorizedSources, int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("CONTEXT_PREPARATION_INVALID");
        var state = checkpoints.load(contextScope);
        if (state == null) throw new IllegalStateException("CONTEXT_PREPARATION_NOT_READY");
        var remainder = ConversationMemoryPreparationPlanner.nextBatch(authorizedSources, state.progress(), 4);
        if (!remainder.complete() || !remainder.slices().isEmpty())
            throw new IllegalStateException("CONTEXT_PREPARATION_NOT_READY");
        JSONObject result = new JSONObject();
        result.put("schemaVersion", "conversation-prepared-context/v1");
        result.put("semanticAuthority", "DERIVED_ONLY");
        result.put("sourceSetDigest", remainder.sourceSetDigest());
        result.put("checkpointRevision", state.checkpoint().revision());
        JSONArray notes = new JSONArray();
        result.put("notes", notes);
        var visited = new HashSet<String>();
        var noteIds = new HashSet<String>();
        var literalSources = new HashSet<String>();
        int duplicateNotes = 0;
        String reference = state.headPage();
        while (reference != null) {
            if (!visited.add(reference) || visited.size() > state.checkpoint().revision())
                throw new IllegalStateException("CONTEXT_MEMORY_CHECKPOINT_CHANGED");
            JSONObject page = checkpoints.loadPage(contextScope, reference);
            if (page == null || !"DERIVED_ONLY".equals(page.getString("semanticAuthority")))
                throw new IllegalStateException("CONTEXT_MEMORY_CHECKPOINT_CHANGED");
            JSONArray pageNotes = page.getJSONObject("notes").getJSONArray("notes");
            // Oldest first: later user requirements remain later, not silently rewritten into old notes.
            for (int i = pageNotes.size() - 1; i >= 0; i--) {
                JSONObject note = pageNotes.getJSONObject(i);
                String literal = fullLiteralIdentity(note);
                if (literal != null && !literalSources.add(literal)) { duplicateNotes++; continue; }
                if (noteIds.add(note.getString("noteId"))) notes.add(0, note);
            }
            if (duplicateNotes > 0) result.put("duplicateNotesOmitted", duplicateNotes);
            if (ContextContractProjection.canonical(result).getBytes(StandardCharsets.UTF_8).length > maxBytes)
                throw new IllegalArgumentException("CONTEXT_PREPARED_NOTES_NEED_PARTITION");
            reference = page.getString("previousPage");
        }
        if (visited.size() != state.checkpoint().revision())
            throw new IllegalStateException("CONTEXT_MEMORY_CHECKPOINT_CHANGED");
        return result;
    }

    /** Collect only primary evidence and deferred references within the active byte budget. */
    public JSONObject readyContextWithOnDemandSources(ConversationContextMemoryStore.Key contextScope,
                                  List<ConversationContextPolicy.Source> authorizedSources, int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("CONTEXT_PREPARATION_INVALID");
        var state = checkpoints.load(contextScope);
        if (state == null) throw new IllegalStateException("CONTEXT_PREPARATION_NOT_READY");
        var remainder = ConversationMemoryPreparationPlanner.nextBatch(authorizedSources, state.progress(), 4);
        if (!remainder.complete() || !remainder.slices().isEmpty())
            throw new IllegalStateException("CONTEXT_PREPARATION_NOT_READY");
        JSONObject result = new JSONObject();
        result.put("schemaVersion", "conversation-prepared-context/v1");
        result.put("semanticAuthority", "DERIVED_ONLY");
        result.put("sourceSetDigest", remainder.sourceSetDigest());
        result.put("checkpointRevision", state.checkpoint().revision());
        JSONArray notes = new JSONArray();
        result.put("notes", notes);
        result = PreparedOnDemandContext.empty(PreparedSourceCoverage.project(result, authorizedSources));
        if (ContextContractProjection.canonical(result).getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("CONTEXT_PREPARED_NOTES_NEED_PARTITION");
        var visited = new HashSet<String>();
        var noteIds = new HashSet<String>();
        var literalSources = new HashSet<String>();
        int duplicateNotes = 0;
        String reference = state.headPage();
        while (reference != null) {
            if (!visited.add(reference) || visited.size() > state.checkpoint().revision())
                throw new IllegalStateException("CONTEXT_MEMORY_CHECKPOINT_CHANGED");
            JSONObject page = checkpoints.loadPage(contextScope, reference);
            if (page == null || !"DERIVED_ONLY".equals(page.getString("semanticAuthority")))
                throw new IllegalStateException("CONTEXT_MEMORY_CHECKPOINT_CHANGED");
            JSONArray pageNotes = page.getJSONObject("notes").getJSONArray("notes");
            // Oldest first: later user requirements remain later, not silently rewritten into old notes.
            for (int i = pageNotes.size() - 1; i >= 0; i--) {
                JSONObject note = pageNotes.getJSONObject(i);
                String literal = fullLiteralIdentity(note);
                if (literal != null && !literalSources.add(literal)) { duplicateNotes++; continue; }
                if (noteIds.add(note.getString("noteId"))) PreparedOnDemandContext.prepend(result, note);
            }
            if (duplicateNotes > 0) result.put("duplicateNotesOmitted", duplicateNotes);
            if (ContextContractProjection.canonical(result).getBytes(StandardCharsets.UTF_8).length > maxBytes)
                throw new IllegalArgumentException("CONTEXT_PREPARED_NOTES_NEED_PARTITION");
            reference = page.getString("previousPage");
        }
        if (visited.size() != state.checkpoint().revision())
            throw new IllegalStateException("CONTEXT_MEMORY_CHECKPOINT_CHANGED");
        PreparedConversationContextContract.require(result);
        return result;
    }

    /** Opt-in projection for consumers that distinguish partial observations from complete processed sources. */
    public JSONObject readyContextWithSourceCoverage(ConversationContextMemoryStore.Key contextScope,
                                                     List<ConversationContextPolicy.Source> authorizedSources, int maxBytes) {
        // The original path verifies full authorized coverage and leaves historical/legacy projections unchanged.
        JSONObject result = PreparedSourceCoverage.project(readyContext(contextScope, authorizedSources, maxBytes), authorizedSources);
        if (ContextContractProjection.canonical(result).getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("CONTEXT_PREPARED_NOTES_NEED_PARTITION");
        PreparedConversationContextContract.require(result);
        return result;
    }

    /** A null batch means the authorized source view is already covered; no preparation model call is needed. */
    public Preparation prepare(ConversationContextMemoryStore.Key executionScope,
                               ConversationContextMemoryStore.Key contextScope,
                               List<ConversationContextPolicy.Source> authorizedSources,
                               int textByteBudget, int maxNotesBytes) {
        return prepare(executionScope, contextScope, authorizedSources, textByteBudget, maxNotesBytes, false);
    }

    /** Small complete sources can be retained literally by the host, without paying for a summarization call. */
    public Preparation prepare(ConversationContextMemoryStore.Key executionScope,
                               ConversationContextMemoryStore.Key contextScope,
                               List<ConversationContextPolicy.Source> authorizedSources,
                               int textByteBudget, int maxNotesBytes, boolean retainSmallExactSources) {
        for (int automaticBatches = 0; ; automaticBatches++) {
            Preparation pending = prepareOnce(executionScope, contextScope, authorizedSources, textByteBudget, maxNotesBytes, retainSmallExactSources);
            if (!retainSmallExactSources || pending.batch() == null || automaticBatches >= 32
                    || !smallCompleteBatch(pending.batch().batch())) return pending;
            var candidates = pending.batch().batch().slices().stream().map(slice ->
                    new ConversationMemoryCheckpointValidator.Candidate(switch (slice.source().kind()) {
                        case USER -> ConversationMemoryCheckpointValidator.NoteKind.USER_REQUIREMENT;
                        case CONFIRMED_INPUT -> ConversationMemoryCheckpointValidator.NoteKind.CONFIRMED_INPUT;
                        case ASSISTANT -> ConversationMemoryCheckpointValidator.NoteKind.ASSISTANT_NOTE;
                        default -> ConversationMemoryCheckpointValidator.NoteKind.SOURCE_NOTE;
                    }, slice.text(), List.of(new ConversationMemoryCheckpointValidator.Citation(
                            slice.source().sourceId(), slice.startOffset(), slice.endOffset(), slice.text())))).toList();
            checkpoints.commitExactSources(pending.batch(), candidates);
        }
    }

    private Preparation prepareOnce(ConversationContextMemoryStore.Key executionScope,
                               ConversationContextMemoryStore.Key contextScope,
                               List<ConversationContextPolicy.Source> authorizedSources,
                               int textByteBudget, int maxNotesBytes, boolean retainSmallExactSources) {
        requireSameOwner(executionScope, contextScope);
        if (maxNotesBytes < 1) throw new IllegalArgumentException("CONTEXT_PREPARATION_INVALID");
        var current = checkpoints.load(contextScope);
        var progress = current == null ? ConversationMemoryPreparationPlanner.Progress.start() : current.progress();
        var batch = ConversationMemoryPreparationPlanner.nextBatch(authorizedSources, progress, textByteBudget);
        // Stop a deterministic literal prefix before a large source. Mixing both used to
        // force the model to re-copy already confirmed JSON and short user constraints.
        if (retainSmallExactSources) {
            int prefixBytes = 0;
            for (var slice : batch.slices()) {
                int bytes = slice.text().getBytes(StandardCharsets.UTF_8).length;
                int limit = slice.source().kind() == ConversationContextPolicy.Kind.CONFIRMED_INPUT ? 8192 : 2048;
                if (slice.startOffset() != 0 || slice.text().isBlank() || bytes > limit
                        || prefixBytes + bytes > 8192
                        || !slice.source().contentHash().equals(cn.hutool.crypto.SecureUtil.sha256(slice.text()))) break;
                prefixBytes += bytes;
            }
            if (prefixBytes >= 4 && prefixBytes < batch.utf8TextBytes())
                batch = ConversationMemoryPreparationPlanner.nextBatch(authorizedSources, progress, prefixBytes);
        }
        if (batch.complete() && batch.slices().isEmpty()) return new Preparation(current, null);
        var anchor = current == null ? ConversationMemoryBatchStore.CheckpointAnchor.initial() : current.checkpoint();
        String reference = batches.save(executionScope, contextScope, anchor, batch, maxNotesBytes);
        return new Preparation(current, batches.load(executionScope, reference));
    }

    private static boolean smallCompleteBatch(ConversationMemoryPreparationPlanner.Batch batch) {
        return !batch.slices().isEmpty() && batch.utf8TextBytes() <= 8192 && batch.slices().stream().allMatch(slice ->
                slice.startOffset() == 0 && !slice.text().isBlank()
                        && slice.text().getBytes(StandardCharsets.UTF_8).length <=
                            (slice.source().kind() == ConversationContextPolicy.Kind.CONFIRMED_INPUT ? 8192 : 2048)
                        && slice.source().contentHash().equals(cn.hutool.crypto.SecureUtil.sha256(slice.text())));
    }

    private static String fullLiteralIdentity(JSONObject note) {
        JSONArray refs = note.getJSONArray("references");
        if (refs == null || refs.size() != 1) return null;
        JSONObject ref = refs.getJSONObject(0);
        String text = note.getString("text");
        if (text == null || ref.getIntValue("startOffset") != 0 || ref.getIntValue("endOffset") != text.length()
                || !cn.hutool.crypto.SecureUtil.sha256(text).equals(ref.getString("contentHash"))) return null;
        return note.getString("kind") + ":" + ref.getString("sourceKind") + ":" + ref.getString("contentHash");
    }

    /** Called for a successful validation receipt, never a model's claimed cursor or completion flag. */
    public ConversationMemoryCheckpointStore.State accept(ConversationContextMemoryStore.Key executionScope,
            String batchReference, List<ConversationContextPolicy.Source> authorizedSources,
            List<ConversationMemoryCheckpointValidator.Candidate> candidates) {
        var stored = batches.load(executionScope, batchReference);
        if (stored == null) throw new IllegalArgumentException("CONTEXT_PREPARATION_BATCH_UNAVAILABLE");
        requireSameOwner(executionScope, stored.contextScope());
        var frozen = stored.batch();
        if (frozen.utf8TextBytes() < 0 || frozen.utf8TextBytes() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("CONTEXT_PREPARATION_INVALID");
        // Recompute the entire source identity and the exact consumed range. A changed,
        // deleted or no-longer-authorized source invalidates the frozen model result.
        var fresh = ConversationMemoryPreparationPlanner.nextBatch(authorizedSources, frozen.start(),
                (int) Math.max(4L, frozen.utf8TextBytes()));
        if (!frozen.equals(fresh))
            throw new IllegalArgumentException("CONTEXT_PREPARATION_SOURCE_CHANGED");
        return checkpoints.commit(stored, candidates);
    }

    private static void requireSameOwner(ConversationContextMemoryStore.Key execution,
                                         ConversationContextMemoryStore.Key context) {
        if (execution == null || context == null || !execution.userId().equals(context.userId()))
            throw new IllegalArgumentException("CONTEXT_PREPARATION_SCOPE_INVALID");
    }
}
