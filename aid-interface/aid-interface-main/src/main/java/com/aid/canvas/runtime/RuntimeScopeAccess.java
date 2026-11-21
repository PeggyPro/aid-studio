package com.aid.canvas.runtime;

import java.util.Date;

/** Authoritative canvas scope derived from a leased assistant runtime run. */
public interface RuntimeScopeAccess {
    /** A confirmed operation is READY while the common executor owns its fresh submission lease. */
    static boolean permitsNewOperation(String status, String nextAction) {
        return "RUNNING".equals(status)
                || "READY".equals(status) && "EXECUTE_PLAN_OPERATION".equals(nextAction);
    }

    WorkspaceScope authorizeWorkspace(Long runId, Long userId, Lease lease,
                                      Long projectId, Long workspaceId);

    /** Authorize a manifest target while allowing a committed receipt to replay after its old revision advanced. */
    Scope authorizeTarget(Long runId, Long userId, Lease lease, Long projectId, Long workspaceId,
                          String objectId, Long objectVersion);

    Scope authorizeExactTarget(Long runId, Long userId, Lease lease, Long projectId, Long workspaceId,
                               String objectId, Long objectVersion);

    Scope reserveTarget(Long runId, Long userId, Lease lease, Long projectId, Long workspaceId,
                        String objectId, Long objectVersion);

    void release(Scope scope);

    record Lease(String owner, Long fencingToken, Date leaseUntil) { }

    record WorkspaceScope(Long runId, Long userId, Long projectId, Long workspaceId, Long workspaceRevision,
                          String leaseOwner, Long runtimeFencingToken, Date leaseUntil) { }

    record Scope(Long runId, Long userId, Long projectId, Long workspaceId, String objectId,
                 Long objectVersion, String leaseOwner, Long runtimeFencingToken,
                 Date leaseUntil, Long nodeFence) {
        WorkspaceScope workspaceScope() {
            return new WorkspaceScope(runId, userId, projectId, workspaceId, null, leaseOwner,
                    runtimeFencingToken, leaseUntil);
        }
    }
}
