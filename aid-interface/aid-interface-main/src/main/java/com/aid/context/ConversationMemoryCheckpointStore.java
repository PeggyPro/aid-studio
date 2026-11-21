package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;

/** Derived notes only. The caller authorizes current sources and supplies a real successful receipt. */
@Service
public class ConversationMemoryCheckpointStore {
    private static final String STATE = "MEMORY_CHECKPOINT_V1";
    private static final String PAGE = "MEMORY_NOTE_PAGE_V1";
    private static final String READY = "MEMORY_READY_CONTEXT_V1";
    private final ConversationContextMemoryStore memory;

    public ConversationMemoryCheckpointStore(ConversationContextMemoryStore memory) {
        this.memory = Objects.requireNonNull(memory);
    }

    public record State(ConversationMemoryBatchStore.CheckpointAnchor checkpoint,
                        ConversationMemoryPreparationPlanner.Progress progress,
                        String headPage, String lastBatch, String notesDigest) { }

    /** A bounded, explicitly versioned projection for freezing a fresh operation; originals stay in their source tables. */
    public boolean saveReadyContext(ConversationContextMemoryStore.Key context, long sourceRevision, JSONObject ready) {
        if (sourceRevision < 0) throw changed();
        PreparedConversationContextContract.require(ready);
        requireCurrentReady(context, ready);
        var storage = key(context, "MEMORY_READY_CONTEXT", null);
        var previous = memory.loadConversationMemory(storage);
        JSONObject body = new JSONObject();
        body.put("kind", READY);
        body.put("sourceRevision", sourceRevision);
        body.put("context", ready);
        String hash = SecureUtil.sha256(ContextContractProjection.canonical(body));
        String json = wrapped(body, hash);
        if (previous != null) {
            JSONObject old = verified(previous, READY);
            if (old.getLongValue("sourceRevision") > sourceRevision) return false;
            if (previous.sourceDigest().equals(hash) && previous.memoryJson().equals(json))
                return previous.equals(memory.loadConversationMemory(storage));
        }
        return memory.saveConversationMemory(storage, previous == null ? 0 : previous.revision(), hash, json);
    }

    public JSONObject loadReadyContext(ConversationContextMemoryStore.Key context, long sourceRevision) {
        var snapshot = memory.loadConversationMemory(key(context, "MEMORY_READY_CONTEXT", null));
        if (snapshot == null) return null;
        JSONObject body = verified(snapshot, READY);
        if (!Long.valueOf(sourceRevision).equals(body.getLong("sourceRevision"))) return null;
        JSONObject ready = body.getJSONObject("context");
        PreparedConversationContextContract.require(ready);
        var current = load(context);
        if (current == null || current.checkpoint().revision() != ready.getLongValue("checkpointRevision")) return null;
        requireCurrentReady(context, ready);
        return ready;
    }

    private void requireCurrentReady(ConversationContextMemoryStore.Key context, JSONObject ready) {
        var current = load(context);
        if (current == null || current.checkpoint().revision() != ready.getLongValue("checkpointRevision")) throw changed();
        JSONObject page = loadPage(context, current.headPage());
        if (page == null || !Objects.equals(page.getString("sourceSetDigest"), ready.getString("sourceSetDigest"))) throw changed();
    }

    public State load(ConversationContextMemoryStore.Key context) {
        var snapshot = memory.loadConversationMemory(key(context, "MEMORY_CHECKPOINT", null));
        if (snapshot == null) return null;
        JSONObject body = verified(snapshot, STATE);
        var progress = JSON.parseObject(body.getJSONObject("progress").toJSONString(),
                ConversationMemoryPreparationPlanner.Progress.class);
        String head = digest(body.getString("headPage"));
        return new State(new ConversationMemoryBatchStore.CheckpointAnchor(snapshot.revision(),
                snapshot.sourceDigest()), progress, head, digest(body.getString("lastBatch")),
                digest(body.getString("notesDigest")));
    }

    /** Revalidate quotes, then CAS the cursor. A lost CAS requires a fresh read, never a blind retry. */
    public State commit(ConversationMemoryBatchStore.Stored frozen,
                        List<ConversationMemoryCheckpointValidator.Candidate> candidates) {
        return commit(frozen, candidates, "VALIDATED_RESULT");
    }

    State commitExactSources(ConversationMemoryBatchStore.Stored frozen,
                            List<ConversationMemoryCheckpointValidator.Candidate> candidates) {
        return commit(frozen, candidates, "EXACT_SOURCE_COPY");
    }

    private State commit(ConversationMemoryBatchStore.Stored frozen,
                         List<ConversationMemoryCheckpointValidator.Candidate> candidates, String preparationMode) {
        Objects.requireNonNull(frozen);
        var result = ConversationMemoryCheckpointValidator.validate(frozen.batch(),
                frozen.batch().batchDigest(), candidates, frozen.maxNotesBytes());
        String notesDigest = SecureUtil.sha256(ContextContractProjection.canonical(
                JSON.parseObject(result.notesJson())));
        State current = load(frozen.contextScope());
        if (current != null && current.lastBatch().equals(frozen.reference())) {
            if (!current.notesDigest().equals(notesDigest)) throw changed();
            return current;
        }
        var anchor = current == null ? ConversationMemoryBatchStore.CheckpointAnchor.initial()
                : current.checkpoint();
        var progress = current == null ? ConversationMemoryPreparationPlanner.Progress.start()
                : current.progress();
        if (!anchor.equals(frozen.checkpoint()) || !progress.equals(frozen.batch().start()))
            throw changed();

        JSONObject page = new JSONObject();
        page.put("kind", PAGE);
        page.put("semanticAuthority", "DERIVED_ONLY");
        page.put("preparationMode", preparationMode);
        page.put("previousPage", current == null ? null : current.headPage());
        page.put("batchReference", frozen.reference());
        page.put("notes", JSON.parseObject(result.notesJson()));
        page.put("sourceSetDigest", result.sourceSetDigest());
        String pageDigest = SecureUtil.sha256(ContextContractProjection.canonical(page));
        // A failed cursor CAS can leave an immutable, unreachable page; it cannot advance coverage.
        if (!memory.saveConversationMemory(key(frozen.contextScope(), "MEMORY_NOTE_PAGE", pageDigest),
                0, pageDigest, wrapped(page, pageDigest))) throw changed();

        JSONObject body = new JSONObject();
        body.put("kind", STATE);
        body.put("headPage", pageDigest);
        body.put("lastBatch", frozen.reference());
        body.put("notesDigest", notesDigest);
        body.put("progress", JSON.parseObject(JSON.toJSONString(result.progress(), JSONWriter.Feature.WriteNulls)));
        String stateDigest = SecureUtil.sha256(ContextContractProjection.canonical(body));
        if (!memory.saveConversationMemory(key(frozen.contextScope(), "MEMORY_CHECKPOINT", null),
                anchor.revision(), stateDigest, wrapped(body, stateDigest))) throw changed();
        return new State(new ConversationMemoryBatchStore.CheckpointAnchor(anchor.revision() + 1, stateDigest),
                result.progress(), pageDigest, frozen.reference(), notesDigest);
    }

    /** One bounded page per call; the consumer chooses which historical notes to load. */
    public JSONObject loadPage(ConversationContextMemoryStore.Key context, String reference) {
        var snapshot = memory.loadConversationMemory(key(context, "MEMORY_NOTE_PAGE", digest(reference)));
        if (snapshot == null) return null;
        if (snapshot.revision() != 1 || !reference.equals(snapshot.sourceDigest())) throw changed();
        return verified(snapshot, PAGE);
    }

    private static JSONObject verified(ConversationContextMemoryStore.Snapshot snapshot, String kind) {
        JSONObject body = JSON.parseObject(snapshot.memoryJson()).getJSONObject("document");
        if (body == null || !kind.equals(body.getString("kind"))
                || !snapshot.sourceDigest().equals(SecureUtil.sha256(ContextContractProjection.canonical(body))))
            throw changed();
        return body;
    }

    private static String wrapped(JSONObject body, String hash) {
        JSONObject wrapper = new JSONObject();
        wrapper.put("schemaVersion", ConversationContextMemoryStore.SCHEMA_VERSION);
        wrapper.put("sourceDigest", hash);
        wrapper.put("document", body);
        return ContextContractProjection.canonical(wrapper);
    }

    private static ConversationContextMemoryStore.Key key(ConversationContextMemoryStore.Key context,
                                                          String type, String reference) {
        Objects.requireNonNull(context);
        JSONObject identity = new JSONObject();
        identity.put("scopeType", context.scopeType());
        identity.put("scopeKey", context.scopeKey());
        identity.put("reference", reference);
        return new ConversationContextMemoryStore.Key(context.userId(), type,
                SecureUtil.sha256(ContextContractProjection.canonical(identity)));
    }

    private static String digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw changed();
        return value;
    }
    private static IllegalStateException changed() {
        return new IllegalStateException("CONTEXT_MEMORY_CHECKPOINT_CHANGED");
    }
}
