package com.aid.context;

import com.aid.context.domain.AidConversationContextMemory;
import com.aid.context.mapper.AidConversationContextMemoryMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Versioned storage shared by conversation consumers. This is not a public read API:
 * the source adapter must verify current conversation access before using this store.
 * A memory revision never conveys permission to read or modify a referenced resource.
 */
@Service
public class ConversationContextMemoryStore {
    public static final String SCHEMA_VERSION = "conversation-context-memory/v1";
    private final AidConversationContextMemoryMapper mapper;
    private final int maxBytes;

    public ConversationContextMemoryStore(AidConversationContextMemoryMapper mapper,
            @Value("${conversation.context.memory.max-bytes:2097152}") int maxBytes) {
        this.mapper = Objects.requireNonNull(mapper);
        if (maxBytes < 1024 || maxBytes > 16 * 1024 * 1024)
            throw new IllegalArgumentException("CONTEXT_MEMORY_LIMIT_INVALID");
        this.maxBytes = maxBytes;
    }

    public record Key(Long userId, String scopeType, String scopeKey) {
        public Key {
            if (userId == null || userId <= 0 || scopeType == null
                    || !scopeType.matches("[A-Z][A-Z0-9_]{0,63}")
                    || scopeKey == null || !scopeKey.matches("[A-Za-z0-9:_-]{1,128}"))
                throw new IllegalArgumentException("CONTEXT_MEMORY_SCOPE_INVALID");
        }
    }

    public record Snapshot(long revision, String sourceDigest, String memoryJson) { }

    /** Derived conversation memory survives a later failure of a model projection/quote. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Snapshot loadConversationMemory(Key key) {
        return load(key);
    }

    /** Only verified historical sources belong here; contracts retain their caller transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean saveConversationMemory(Key key, long expectedRevision,
                                          String sourceDigest, String memoryJson) {
        return save(key, expectedRevision, sourceDigest, memoryJson);
    }

    public Snapshot load(Key key) {
        Objects.requireNonNull(key);
        AidConversationContextMemory row = mapper.findActive(key.userId(), key.scopeType(), key.scopeKey());
        if (row == null) return null;
        if (!Objects.equals(key.userId(), row.getUserId())
                || !Objects.equals(key.scopeType(), row.getScopeType())
                || !Objects.equals(key.scopeKey(), row.getScopeKey())
                || !"0".equals(row.getDelFlag()) || row.getRevision() == null || row.getRevision() < 1)
            throw new IllegalStateException("CONTEXT_MEMORY_SCOPE_MISMATCH");
        validateDocument(row.getSourceDigest(), row.getMemoryJson());
        return new Snapshot(row.getRevision(), row.getSourceDigest(), row.getMemoryJson());
    }

    /**
     * expectedRevision=0 initializes without overwriting an existing snapshot.
     * A false result requires re-reading and recomputing from sources, never blind retry.
     * Source adapters skip this method for unchanged source/summary documents.
     */
    public boolean save(Key key, long expectedRevision, String sourceDigest, String memoryJson) {
        Objects.requireNonNull(key);
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE)
            throw new IllegalArgumentException("CONTEXT_MEMORY_REVISION_INVALID");
        validateDocument(sourceDigest, memoryJson);
        if (expectedRevision == 0) {
            mapper.insertIfAbsent(key.userId(), key.scopeType(), key.scopeKey(), sourceDigest, memoryJson);
            Snapshot stored = load(key);
            return stored != null && stored.revision() == 1
                    && stored.sourceDigest().equals(sourceDigest) && stored.memoryJson().equals(memoryJson);
        }
        return mapper.compareAndSet(key.userId(), key.scopeType(), key.scopeKey(), expectedRevision,
                sourceDigest, memoryJson) == 1;
    }

    /** One scope, immutable revision-one documents only; unchanged rows are never written. */
    public boolean saveImmutableBatch(Long userId, String scopeType, java.util.Map<String, Snapshot> documents) {
        if (!"CONTEXT_CONTRACT".equals(scopeType) || documents == null || documents.isEmpty() || documents.size() > 128)
            throw new IllegalArgumentException("CONTEXT_IMMUTABLE_BATCH_INVALID");
        for (var entry : documents.entrySet()) {
            new Key(userId, scopeType, entry.getKey());
            Snapshot snapshot = Objects.requireNonNull(entry.getValue());
            if (snapshot.revision() != 1) throw new IllegalArgumentException("CONTEXT_IMMUTABLE_REVISION_INVALID");
            validateDocument(snapshot.sourceDigest(), snapshot.memoryJson());
        }
        var existing = immutableRows(userId, scopeType, documents,
                mapper.findActiveBatch(userId, scopeType, java.util.List.copyOf(documents.keySet())));
        if (existing == null) return false;
        java.util.List<AidConversationContextMemory> missing = new java.util.ArrayList<>();
        for (var entry : documents.entrySet()) {
            if (existing.contains(entry.getKey())) continue;
            var row = new AidConversationContextMemory();
            row.setScopeKey(entry.getKey()); row.setSourceDigest(entry.getValue().sourceDigest());
            row.setMemoryJson(entry.getValue().memoryJson()); missing.add(row);
        }
        if (missing.isEmpty()) return true;
        mapper.insertImmutableBatchIfAbsent(userId, scopeType, missing);
        var confirmed = immutableRows(userId, scopeType, documents,
                mapper.findActiveBatch(userId, scopeType, missing.stream().map(AidConversationContextMemory::getScopeKey).toList()));
        return confirmed != null && confirmed.size() == missing.size();
    }

    private java.util.Set<String> immutableRows(Long userId, String scopeType,
            java.util.Map<String, Snapshot> wanted, java.util.List<AidConversationContextMemory> rows) {
        if (rows == null) return null;
        java.util.Set<String> found = new java.util.HashSet<>();
        for (var row : rows) {
            if (row == null || !Objects.equals(userId, row.getUserId()) || !Objects.equals(scopeType, row.getScopeType())
                    || !"0".equals(row.getDelFlag()) || row.getRevision() == null || row.getRevision() != 1
                    || !wanted.containsKey(row.getScopeKey()) || !found.add(row.getScopeKey())) return null;
            Snapshot expected = wanted.get(row.getScopeKey());
            if (!Objects.equals(expected.sourceDigest(), row.getSourceDigest())
                    || !Objects.equals(expected.memoryJson(), row.getMemoryJson())) return null;
        }
        return found;
    }

    private void validateDocument(String sourceDigest, String memoryJson) {
        if (sourceDigest == null || !sourceDigest.matches("[a-f0-9]{64}") || memoryJson == null
                || memoryJson.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("CONTEXT_MEMORY_DOCUMENT_INVALID");
        final Object parsed;
        try { parsed = JSON.parse(memoryJson); }
        catch (RuntimeException invalid) {
            throw new IllegalArgumentException("CONTEXT_MEMORY_DOCUMENT_INVALID", invalid);
        }
        if (!(parsed instanceof JSONObject document)
                || !SCHEMA_VERSION.equals(document.getString("schemaVersion"))
                || !sourceDigest.equals(document.getString("sourceDigest")))
            throw new IllegalArgumentException("CONTEXT_MEMORY_DOCUMENT_INVALID");
    }
}
