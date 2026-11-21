package com.aid.runtime.tool;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Trusted current scope used to hide optional Tools that cannot run here. */
@Data
public class RuntimeToolAvailabilityContext
{
    private Long runId;
    private Long userId;
    private String moduleCode;
    private Long projectId;
    private Long workspaceId;
    private String contextManifestJson;
    private Object targetRefs;
    /** Optional server-owned Tool requirement for the current immutable request. */
    private String requiredToolCode;
    /** Server-only budget discovery can offer Tools for future, separately proven own targets. */
    private boolean budgetDiscovery;
    /** Planner metadata only; execution, quotation and budget admission never set this flag. */
    private boolean metadataDiscovery;
    /** Optional server-owned model scope; null preserves ordinary catalog discovery. */
    private java.util.List<String> candidateModelCodes;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient Map<Object, Object> discoverySnapshots;

    /** One registry discovery only; nested provider discovery shares it, later calls reload. */
    public synchronized <T> T withDiscoverySnapshot(Supplier<T> discovery)
    {
        if (discoverySnapshots != null) return discovery.get();
        discoverySnapshots = new HashMap<>();
        try { return discovery.get(); }
        finally { discoverySnapshots = null; }
    }

    /** Metadata only. Never memoize access decisions, quotes or execution authorization. */
    @SuppressWarnings("unchecked")
    public synchronized <T> T discoveryValue(Object key, Supplier<T> loader)
    {
        if (discoverySnapshots == null) return loader.get();
        return (T) discoverySnapshots.computeIfAbsent(key, ignored -> loader.get());
    }

}
