package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable, owner/scope-bound contract documents in the shared context store.
 * Callers must recheck live source permissions and fixed versions before saving or reading.
 * A cached contract is discovery data, never an execution or billing authorization.
 */
@Service
public class ContextContractStore {
    private static final String DOCUMENT_KIND = "IMMUTABLE_CONTRACT_V1";
    private final ConversationContextMemoryStore memory;

    public ContextContractStore(ConversationContextMemoryStore memory) {
        this.memory = Objects.requireNonNull(memory);
    }

    public record Chunk(String reference, int offset, String text,
                        Integer nextOffset, boolean hasMore, int totalChars) { }

    public void save(ConversationContextMemoryStore.Key scope, String reference, String documentJson) {
        Objects.requireNonNull(scope);
        JSONObject document = verifiedDocument(reference, documentJson);
        JSONObject wrapper = new JSONObject();
        wrapper.put("schemaVersion", ConversationContextMemoryStore.SCHEMA_VERSION);
        wrapper.put("sourceDigest", reference);
        wrapper.put("documentKind", DOCUMENT_KIND);
        wrapper.put("sourceScopeType", scope.scopeType());
        wrapper.put("sourceScopeKey", scope.scopeKey());
        wrapper.put("document", document);
        if (!memory.save(storageKey(scope, reference), 0, reference,
                ContextContractProjection.canonical(wrapper)))
            throw new IllegalStateException("CONTEXT_CONTRACT_DOCUMENT_CHANGED");
    }

    /** Bounded batches reuse the same immutable contract and owner/scope identities. */
    public void saveAll(ConversationContextMemoryStore.Key scope, java.util.Map<String, String> documents) {
        Objects.requireNonNull(scope); Objects.requireNonNull(documents);
        java.util.Map<String, ConversationContextMemoryStore.Snapshot> batch = new java.util.LinkedHashMap<>();
        int bytes = 0;
        for (var entry : documents.entrySet()) {
            String reference = entry.getKey();
            JSONObject document = verifiedDocument(reference, entry.getValue());
            JSONObject wrapper = new JSONObject();
            wrapper.put("schemaVersion", ConversationContextMemoryStore.SCHEMA_VERSION);
            wrapper.put("sourceDigest", reference); wrapper.put("documentKind", DOCUMENT_KIND);
            wrapper.put("sourceScopeType", scope.scopeType()); wrapper.put("sourceScopeKey", scope.scopeKey());
            wrapper.put("document", document);
            String encoded = ContextContractProjection.canonical(wrapper);
            int length = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (!batch.isEmpty() && (batch.size() >= 128 || bytes + length > 524288)) {
                saveBatch(scope, batch); batch.clear(); bytes = 0;
            }
            batch.put(storageKey(scope, reference).scopeKey(), new ConversationContextMemoryStore.Snapshot(1, reference, encoded));
            bytes += length;
        }
        if (!batch.isEmpty()) saveBatch(scope, batch);
    }

    private void saveBatch(ConversationContextMemoryStore.Key scope,
            java.util.Map<String, ConversationContextMemoryStore.Snapshot> batch) {
        if (!memory.saveImmutableBatch(scope.userId(), "CONTEXT_CONTRACT", batch))
            throw new IllegalStateException("CONTEXT_CONTRACT_SAVE_CONFLICT");
    }

    public String load(ConversationContextMemoryStore.Key scope, String reference) {
        Objects.requireNonNull(scope);
        var snapshot = memory.load(storageKey(scope, reference));
        if (snapshot == null) return null;
        JSONObject wrapper = JSON.parseObject(snapshot.memoryJson());
        if (snapshot.revision() != 1 || !reference.equals(snapshot.sourceDigest())
                || !DOCUMENT_KIND.equals(wrapper.getString("documentKind"))
                || !scope.scopeType().equals(wrapper.getString("sourceScopeType"))
                || !scope.scopeKey().equals(wrapper.getString("sourceScopeKey"))
                || !(wrapper.get("document") instanceof JSONObject document))
            throw new IllegalStateException("CONTEXT_CONTRACT_SCOPE_CHANGED");
        String serialized = ContextContractProjection.canonical(document);
        verifiedDocument(reference, serialized);
        return serialized;
    }

    /** UTF-16 cursors match the existing history reader; a surrogate pair is never split. */
    public Chunk chunk(ConversationContextMemoryStore.Key scope, String reference, int offset, int maxChars) {
        if (offset < 0 || maxChars < 1 || maxChars > 8192)
            throw new IllegalArgumentException("CONTEXT_CONTRACT_CHUNK_INVALID");
        String text = load(scope, reference);
        if (text == null) throw new IllegalStateException("CONTEXT_CONTRACT_DOCUMENT_UNAVAILABLE");
        return chunkLoaded(reference, text, offset, maxChars);
    }

    static Chunk chunkLoaded(String reference, String text, int offset, int maxChars) {
        if (offset < 0 || maxChars < 1 || maxChars > 8192)
            throw new IllegalArgumentException("CONTEXT_CONTRACT_CHUNK_INVALID");
        if (offset > text.length() || offset > 0 && offset < text.length()
                && Character.isLowSurrogate(text.charAt(offset))
                && Character.isHighSurrogate(text.charAt(offset - 1)))
            throw new IllegalArgumentException("CONTEXT_CONTRACT_CHUNK_INVALID");
        int end = (int) Math.min(text.length(), (long) offset + maxChars);
        if (end > offset && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end++;
        boolean more = end < text.length();
        return new Chunk(reference, offset, text.substring(offset, end), more ? end : null, more, text.length());
    }

    private static ConversationContextMemoryStore.Key storageKey(
            ConversationContextMemoryStore.Key scope, String reference) {
        requireReference(reference);
        JSONObject identity = new JSONObject();
        identity.put("scopeType", scope.scopeType());
        identity.put("scopeKey", scope.scopeKey());
        identity.put("reference", reference);
        return new ConversationContextMemoryStore.Key(scope.userId(), "CONTEXT_CONTRACT",
                SecureUtil.sha256(ContextContractProjection.canonical(identity)));
    }

    private static JSONObject verifiedDocument(String reference, String json) {
        requireReference(reference);
        JSONObject document;
        try { document = json == null ? null : JSON.parseObject(json); }
        catch (RuntimeException malformed) { throw invalid(); }
        if (document == null || !ContextContractProjection.VERSION.equals(document.getString("schemaVersion"))
                || document.getString("kind") == null || !Set.of("TOOL", "SKILL").contains(document.getString("kind"))
                || !(document.get("entry") instanceof JSONObject)
                || !reference.equals(SecureUtil.sha256(ContextContractProjection.canonical(document))))
            throw invalid();
        return document;
    }

    private static void requireReference(String reference) {
        if (reference == null || !reference.matches("[a-f0-9]{64}")) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTEXT_CONTRACT_DOCUMENT_INVALID");
    }
}
