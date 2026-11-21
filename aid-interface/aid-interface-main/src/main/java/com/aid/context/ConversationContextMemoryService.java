package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared persistence/selection, independent of Skill identities and Agent protocols. */
@Service
public class ConversationContextMemoryService {
    private final ConversationContextMemoryStore store;

    public ConversationContextMemoryService(ConversationContextMemoryStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public record Ticket(ConversationContextMemoryStore.Key key,
                         ConversationContextMemoryStore.Snapshot snapshot) { }

    public record Result(ConversationContextPolicy.Selection selection, boolean memoryPersisted) { }

    /** Call after live scope authorization and BEFORE querying its authoritative sources. */
    public Ticket begin(ConversationContextMemoryStore.Key key) {
        return new Ticket(key, store.loadConversationMemory(key));
    }

    public Result selectAndRemember(Ticket ticket, long sourceRevision,
                                    List<ConversationContextPolicy.Source> sources,
                                    Set<String> requiredSourceIds, int recentOptionalCount,
                                    long textByteBudget) {
        if (textByteBudget < 0) throw new IllegalArgumentException("CONTEXT_SELECTION_INVALID");
        // Validate identities and explicit dependencies before any persistence. The
        // provider's smaller projection budget must not decide whether sources exist.
        ConversationContextPolicy.select(sources, requiredSourceIds, recentOptionalCount, Long.MAX_VALUE);
        boolean persisted = remember(ticket, sourceRevision, sources);
        var selection = ConversationContextPolicy.select(sources, requiredSourceIds,
                recentOptionalCount, textByteBudget);
        return new Result(selection, persisted);
    }

    /** Remember verified sources independently of any model's context allowance. */
    public boolean remember(Ticket ticket, long sourceRevision,
                            List<ConversationContextPolicy.Source> sources) {
        Objects.requireNonNull(ticket);
        Objects.requireNonNull(ticket.key());
        if (sourceRevision < 0) throw new IllegalArgumentException("CONTEXT_SOURCE_REVISION_INVALID");
        ConversationContextPolicy.select(sources, Set.of(), 0, Long.MAX_VALUE);
        List<ConversationContextPolicy.Source> ordered = sources.stream()
                .sorted(Comparator.comparingLong(ConversationContextPolicy.Source::sequence)
                        .thenComparing(ConversationContextPolicy.Source::sourceId)).toList();
        JSONArray keySources = new JSONArray();
        JSONArray sourceIndex = new JSONArray();
        for (var source : ordered) {
            JSONObject identity = new JSONObject();
            identity.put("sourceId", source.sourceId());
            identity.put("sequence", source.sequence());
            identity.put("kind", source.kind().name());
            var metrics = TextContentMetrics.measure(source.text());
            identity.put("contentHash", metrics.sha256());
            identity.put("utf8Bytes", metrics.utf8Bytes());
            identity.put("estimatedQuarterTokens", metrics.quarterTokens());
            sourceIndex.add(identity);
            if (source.mandatory()) {
                JSONObject exact = new JSONObject(identity);
                exact.put("text", source.text());
                keySources.add(exact);
            }
        }
        String digest = SecureUtil.sha256(sourceIndex.toJSONString());
        JSONObject document = new JSONObject();
        document.put("schemaVersion", ConversationContextMemoryStore.SCHEMA_VERSION);
        document.put("sourceDigest", digest);
        document.put("sourceRevision", sourceRevision);
        document.put("keySources", keySources);
        document.put("sourceIndex", sourceIndex);
        document.put("summaryAuthority", "DERIVED_ONLY");
        document.put("sourcePolicy", "Current source access and business state must be rechecked; this memory grants no resource or tool permission.");

        var previous = ticket.snapshot();
        if (previous != null) {
            JSONObject old = JSON.parseObject(previous.memoryJson());
            if (old.getLongValue("sourceRevision") > sourceRevision) return false;
            if (previous.sourceDigest().equals(digest)
                    && old.getLongValue("sourceRevision") == sourceRevision) {
                // A stale ticket cannot claim that its old snapshot is still persisted.
                var current = store.loadConversationMemory(ticket.key());
                return previous.equals(current);
            }
        }
        // A competing writer wins atomically. Do not overwrite it or retry an old source view.
        return store.saveConversationMemory(ticket.key(), previous == null ? 0 : previous.revision(),
                digest, document.toJSONString());
    }
}
