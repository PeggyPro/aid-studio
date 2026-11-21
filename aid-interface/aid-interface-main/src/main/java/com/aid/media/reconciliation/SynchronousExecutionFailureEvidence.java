package com.aid.media.reconciliation;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.media.MediaExecutionPermit;
import lombok.Data;
import java.util.Objects;

/** Local execution evidence, deliberately distinct from an upstream generation failure. */
@Data
public class SynchronousExecutionFailureEvidence {
    public static final String TYPE = "LOCAL_SYNCHRONOUS_EXECUTION";
    public static final String STATUS = "LOCAL_SYNC_CONNECTION_LOST";
    public static final String MESSAGE = "同步生成连接已结束，且未保存可恢复的结果，请重试";
    private String executionToken;
    private String releaseReason;
    private String legacyRequestDigest;
    private String legacyRouteDigest;

    public static SynchronousExecutionFailureEvidence capture(AidMediaTask task, MediaExecutionPermit permit) {
        if (task == null || !"PENDING".equals(task.getStatus()) || !eligible(task, permit)) return null;
        if ("LEGACY".equals(permit.getExecutionMode())
                && task.getErrorDetailJson() != null && !task.getErrorDetailJson().isBlank()) return null;
        var evidence = new SynchronousExecutionFailureEvidence();
        evidence.setExecutionToken(permit.getToken());
        evidence.setReleaseReason(permit.getReleaseReason());
        if (LegacySynchronousTextEvidence.matches(task, permit)) {
            evidence.setLegacyRequestDigest(cn.hutool.crypto.digest.DigestUtil.sha256Hex(task.getRequestJson()));
            evidence.setLegacyRouteDigest(cn.hutool.crypto.digest.DigestUtil.sha256Hex(task.getProviderRouteSnapshotJson()));
        }
        return evidence;
    }

    public boolean matches(AidMediaTask task, MediaExecutionPermit permit) {
        return eligible(task, permit) && Objects.equals(executionToken, permit.getToken())
                && Objects.equals(releaseReason, permit.getReleaseReason())
                && (!"LEGACY".equals(permit.getExecutionMode())
                    || Objects.equals(legacyRequestDigest, cn.hutool.crypto.digest.DigestUtil.sha256Hex(task.getRequestJson()))
                    && Objects.equals(legacyRouteDigest, cn.hutool.crypto.digest.DigestUtil.sha256Hex(task.getProviderRouteSnapshotJson())));
    }

    private static boolean eligible(AidMediaTask task, MediaExecutionPermit permit) {
        boolean legacyText = LegacySynchronousTextEvidence.matches(task, permit);
        return task != null && permit != null
                && (legacyText || java.util.Set.of("SYNC_TEXT", "SYNC_IMAGE", "SYNC_AUDIO", "SUBMITTING")
                        .contains(permit.getExecutionMode()))
                && (task.getProviderTaskId() == null || task.getProviderTaskId().isBlank())
                && task.getExecutionPermitToken() != null && !task.getExecutionPermitToken().isBlank()
                && Objects.equals(task.getExecutionPermitToken(), permit.getToken())
                && Objects.equals(task.getId(), permit.getTaskId())
                && "RELEASED".equals(permit.getState())
                && permit.getReleasedAt() != null
                && ("LOCAL_TEXT_CONNECTION_CLOSED".equals(permit.getReleaseReason())
                    || "LOCAL_SYNC_CONNECTION_CLOSED".equals(permit.getReleaseReason())
                    || "LOCAL_SYNC_RESPONSE_COMPLETED".equals(permit.getReleaseReason())
                    || "LOCAL_EXECUTION_LEASE_EXPIRED".equals(permit.getReleaseReason()) || legacyText);
    }
}
