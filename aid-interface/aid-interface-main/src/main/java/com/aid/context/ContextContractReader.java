package com.aid.context;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared on-demand reader. Each consumer supplies its live authorization and catalog adapter. */
@Service
public class ContextContractReader {
    /** A successful authorized lookup proved that this immutable source is absent. */
    public static final class DocumentUnavailable extends IllegalStateException {
        public DocumentUnavailable() { super("CONTEXT_CONTRACT_DOCUMENT_UNAVAILABLE"); }
    }
    public interface ScopeAccess {
        String scopeType();

        /** Must verify current user access, frozen bindings and live source visibility. */
        JSONObject authorize(ConversationContextMemoryStore.Key scope);
    }

    private final ContextContractStore store;
    private final Map<String, ScopeAccess> scopes;

    public ContextContractReader(ContextContractStore store, List<ScopeAccess> scopeAccess) {
        this.store = Objects.requireNonNull(store);
        Map<String, ScopeAccess> values = new HashMap<>();
        for (ScopeAccess access : Objects.requireNonNull(scopeAccess)) {
            if (access == null || access.scopeType() == null
                    || !access.scopeType().matches("[A-Z][A-Z0-9_]{0,63}")
                    || values.putIfAbsent(access.scopeType(), access) != null)
                throw new IllegalArgumentException("CONTEXT_CONTRACT_SCOPE_PROVIDER_INVALID");
        }
        scopes = Map.copyOf(values);
    }

    public ContextContractStore.Chunk read(ConversationContextMemoryStore.Key scope,
                                          String reference, int offset, int maxChars) {
        Objects.requireNonNull(scope);
        ScopeAccess access = scopes.get(scope.scopeType());
        if (access == null) throw new IllegalStateException("CONTEXT_CONTRACT_SCOPE_UNAVAILABLE");
        // Do this for every chunk, including cache hits; a persisted hash never retains revoked access.
        JSONObject currentCatalog = access.authorize(scope);
        if (currentCatalog == null) throw new IllegalStateException("CONTEXT_CONTRACT_ACCESS_UNAVAILABLE");
        String document = store.load(scope, reference);
        if (document == null) throw new DocumentUnavailable();
        ContextContractAccessPolicy.requireCurrent(JSON.parseObject(document), currentCatalog);
        return ContextContractStore.chunkLoaded(reference, document, offset, maxChars);
    }
}
