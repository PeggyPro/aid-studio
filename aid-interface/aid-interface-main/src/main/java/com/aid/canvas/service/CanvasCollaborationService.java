package com.aid.canvas.service;

import com.aid.canvas.dto.CanvasRequests;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 工作区在线会话与节点租约。共享业务写入仍由 HTTP Service 完成。 */
public interface CanvasCollaborationService {
    Map<String, Object> createTicket(CanvasRequests.Ticket request, Long userId);
    Map<String, Object> state(Long workspaceId, Long userId);
    SessionView consumeTicket(String ticket, String clientIp, Consumer<String> outbound);
    void heartbeat(String sessionId, Long acknowledgedSeq);
    void closeSession(String sessionId, String reason);
    List<Map<String, Object>> acquire(CanvasRequests.LockAcquire request, Long userId);
    List<Map<String, Object>> renew(CanvasRequests.LockChange request, Long userId);
    void release(CanvasRequests.LockChange request, Long userId);
    void assertEditLock(Long workspaceId, String objectId, String sessionId, String lockToken, Long userId);
    void requireEditSession(Long workspaceId, String sessionId, Long userId);
    long requireEditFence(Long workspaceId, String objectId, String sessionId, String lockToken, Long userId);
    void beginRuntimeLock(Long workspaceId, String objectId, String sessionId, String editToken,
                          Long userId, Long runId);
    void renewRuntimeLock(Long workspaceId, String objectId, Long userId, Long runId);
    void releaseRuntimeLock(Long workspaceId, String objectId, Long userId, Long runId);
    long beginAssistantRuntimeLock(Long workspaceId, String objectId, Long expectedVersion,
                                   Long userId, Long runId, Long runtimeFencingToken);
    long requireAssistantRuntimeFence(Long workspaceId, String objectId, Long expectedVersion,
                                      Long userId, Long runId, Long runtimeFencingToken);
    /** DB-only fence assertion used after Runtime claim inside the same mutation transaction. */
    long requireAssistantRuntimeFenceForMutation(Long workspaceId, String objectId, Long expectedVersion,
                                                 Long expectedNodeFence, Long userId, Long runId);
    void renewAssistantRuntimeLock(Long workspaceId, String objectId, Long expectedVersion,
                                   Long userId, Long runId, Long runtimeFencingToken);
    void releaseAssistantRuntimeLock(Long workspaceId, String objectId, Long userId,
                                     Long runId, Long runtimeFencingToken);
    void beginTaskLock(Long workspaceId, String objectId, String sessionId, String editToken, Long userId,
                       String operationId);
    /** Verify the exact task owner, including the brief committed-task-before-bind handoff. */
    void requireAssistantMediaTaskLock(Long workspaceId, String objectId, Long expectedVersion,
                                       Long userId, String operationId, String callId, Long taskId);
    /** Convert only the exact Assistant reservation into its already-persisted task intent. */
    void beginAssistantTaskLock(Long workspaceId, String objectId, Long expectedVersion,
                                Long userId, Long runId, Long runtimeFencingToken, Long nodeFence,
                                String operationId, String callId);
    void bindTaskLock(Long workspaceId, String objectId, Long userId, String operationId, Long taskId);
    void releasePendingTaskLock(Long workspaceId, String objectId, Long userId, String operationId);
    void releaseTaskLock(Long workspaceId, String objectId, Long taskId);
    void renewTaskLock(Long workspaceId, String objectId, Long taskId);
    void publishWorkspaceEvent(Long workspaceId, String eventJson);
    List<Map<String, Object>> listSessions(Long projectId, Long userId);
    List<Map<String, Object>> listLocks(Long projectId, Long workspaceId, Long userId);
    int onlineCount(Long workspaceId);
    boolean terminateSession(String sessionId, String reason);
    void acceptClusterMessage(String message);
    void cleanupIdleSessions();

    record SessionView(String sessionId, Long workspaceId, Long projectId, Long userId, String pageId) { }
}
