package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Immutable preparation inputs; callers must recheck live source access before every use. */
@Service
public class ConversationMemoryBatchStore {
    private static final String KIND = "IMMUTABLE_MEMORY_BATCH_V1";
    private final ConversationContextMemoryStore memory;
    public ConversationMemoryBatchStore(ConversationContextMemoryStore memory) {
        this.memory = Objects.requireNonNull(memory);
    }
    public record CheckpointAnchor(long revision, String digest) {
        public CheckpointAnchor {
            if (revision < 0 || (revision == 0 ? digest != null
                    : digest == null || !digest.matches("[a-f0-9]{64}")))
                throw new IllegalArgumentException("CONTEXT_MEMORY_CHECKPOINT_ANCHOR_INVALID");
        }
        public static CheckpointAnchor initial() { return new CheckpointAnchor(0, null); }
    }
    public record Stored(String reference, ConversationContextMemoryStore.Key contextScope,
                         CheckpointAnchor checkpoint,
                         ConversationMemoryPreparationPlanner.Batch batch,
                         int maxNotesBytes) { }
    public String save(ConversationContextMemoryStore.Key scope,
                       ConversationMemoryPreparationPlanner.Batch batch, int maxNotesBytes) {
        return save(scope, scope, batch, maxNotesBytes);
    }

    /** Bind the executing request and the durable conversation independently. */
    public String save(ConversationContextMemoryStore.Key scope,
                       ConversationContextMemoryStore.Key contextScope,
                       ConversationMemoryPreparationPlanner.Batch batch, int maxNotesBytes) {
        return save(scope, contextScope, CheckpointAnchor.initial(), batch, maxNotesBytes);
    }

    public String save(ConversationContextMemoryStore.Key scope,
                       ConversationContextMemoryStore.Key contextScope, CheckpointAnchor checkpoint,
                       ConversationMemoryPreparationPlanner.Batch batch, int maxNotesBytes) {
        Objects.requireNonNull(scope);
        if (checkpoint == null || contextScope == null || !scope.userId().equals(contextScope.userId())
                || batch == null || batch.start() == null || batch.next() == null || maxNotesBytes < 1)
            throw new IllegalArgumentException("CONTEXT_MEMORY_BATCH_INVALID");
        JSONObject document = new JSONObject();
        document.put("format", KIND);
        document.put("scopeUserId", String.valueOf(scope.userId()));
        document.put("scopeType", scope.scopeType());
        document.put("scopeKey", scope.scopeKey());
        document.put("contextScopeType", contextScope.scopeType());
        document.put("contextScopeKey", contextScope.scopeKey());
        document.put("checkpointRevision", checkpoint.revision());
        document.put("checkpointDigest", checkpoint.digest());
        document.put("maxNotesBytes", maxNotesBytes);
        document.put("batch", encoded(batch));
        String reference = SecureUtil.sha256(ContextContractProjection.canonical(document));
        JSONObject wrapper = new JSONObject();
        wrapper.put("schemaVersion", ConversationContextMemoryStore.SCHEMA_VERSION);
        wrapper.put("sourceDigest", reference);
        wrapper.put("documentKind", KIND);
        wrapper.put("document", document);
        if (!memory.saveConversationMemory(storageKey(scope, reference), 0, reference,
                ContextContractProjection.canonical(wrapper)))
            throw new IllegalStateException("CONTEXT_MEMORY_BATCH_CHANGED");
        return reference;
    }
    public Stored load(ConversationContextMemoryStore.Key scope, String reference) {
        Objects.requireNonNull(scope);
        var snapshot = memory.loadConversationMemory(storageKey(scope, reference));
        if (snapshot == null) return null;
        try {
            JSONObject wrapper = JSON.parseObject(snapshot.memoryJson());
            JSONObject document = wrapper.getJSONObject("document");
            if (snapshot.revision() != 1 || !reference.equals(snapshot.sourceDigest())
                    || !KIND.equals(wrapper.getString("documentKind")) || document == null
                    || !KIND.equals(document.getString("format"))
                    || !String.valueOf(scope.userId()).equals(document.getString("scopeUserId"))
                    || !scope.scopeType().equals(document.getString("scopeType"))
                    || !scope.scopeKey().equals(document.getString("scopeKey"))
                    || !reference.equals(SecureUtil.sha256(ContextContractProjection.canonical(document))))
                throw new IllegalStateException("CONTEXT_MEMORY_BATCH_CHANGED");
            Integer notesBytes = document.getInteger("maxNotesBytes");
            JSONObject rawBatch = document.getJSONObject("batch");
            if (notesBytes == null || notesBytes < 1 || rawBatch == null)
                throw new IllegalStateException("CONTEXT_MEMORY_BATCH_INVALID");
            var batch = JSON.parseObject(rawBatch.toJSONString(), ConversationMemoryPreparationPlanner.Batch.class);
            if (batch == null || batch.start() == null || batch.next() == null
                    || !ContextContractProjection.canonical(rawBatch).equals(
                    ContextContractProjection.canonical(encoded(batch))))
                throw new IllegalStateException("CONTEXT_MEMORY_BATCH_INVALID");
            var contextScope = new ConversationContextMemoryStore.Key(scope.userId(),
                    document.getString("contextScopeType"), document.getString("contextScopeKey"));
            Long checkpointRevision = document.getLong("checkpointRevision");
            if (checkpointRevision == null)
                throw new IllegalStateException("CONTEXT_MEMORY_CHECKPOINT_ANCHOR_INVALID");
            var checkpoint = new CheckpointAnchor(checkpointRevision, document.getString("checkpointDigest"));
            return new Stored(reference, contextScope, checkpoint, batch, notesBytes);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("CONTEXT_MEMORY_BATCH_INVALID", invalid);
        }
    }

    private static JSONObject encoded(ConversationMemoryPreparationPlanner.Batch batch) {
        return JSON.parseObject(JSON.toJSONString(batch, JSONWriter.Feature.WriteNulls));
    }

    private static ConversationContextMemoryStore.Key storageKey(
            ConversationContextMemoryStore.Key scope, String reference) {
        if (reference == null || !reference.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("CONTEXT_MEMORY_BATCH_REFERENCE_INVALID");
        JSONObject identity = new JSONObject();
        identity.put("scopeType", scope.scopeType());
        identity.put("scopeKey", scope.scopeKey());
        identity.put("reference", reference);
        return new ConversationContextMemoryStore.Key(scope.userId(), "MEMORY_SOURCE_BATCH",
                SecureUtil.sha256(ContextContractProjection.canonical(identity)));
    }
}
