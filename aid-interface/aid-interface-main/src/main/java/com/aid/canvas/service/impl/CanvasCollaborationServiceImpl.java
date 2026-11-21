package com.aid.canvas.service.impl;

import com.aid.aid.domain.AidComicProject;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.domain.CanvasEntities.TaskBinding;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.entitlement.service.EntitlementSubjectResolver;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class CanvasCollaborationServiceImpl implements CanvasCollaborationService {
    private static final String TICKET_PREFIX = "canvas:ticket:";
    private static final String LOCK_PREFIX = "canvas:lock:";
    private static final String SESSION_PREFIX = "canvas:session:";
    public static final String EVENT_CHANNEL = "canvas:cluster:event";
    private static final int TICKET_TTL_SECONDS = 60;
    private static final int TASK_LOCK_TTL_SECONDS = 90;
    private static final int PENDING_TASK_LOCK_TTL_SECONDS = 5 * 60;
    private static final long SESSION_IDLE_MILLIS = 45_000L;
    private static final Set<String> TERMINAL_TASK_STATUS = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> ACTIVE_RUN_STATUS = Set.of("CREATED", "RUNNING", "WAITING_INPUT");

    private final CanvasAccessService accessService;
    private final CanvasMapper canvasMapper;
    private final AidRuntimeRunMapper runtimeRunMapper;
    private final RedisCache redisCache;
    private final RedissonClient redissonClient;
    private final EntitlementSubjectResolver entitlementSubjectResolver;
    private final Map<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString().replace("-", "");

    @Override
    public Map<String, Object> createTicket(CanvasRequests.Ticket request, Long userId) {
        Workspace workspace = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR", "VIEWER");
        Map<String, Object> config = accessService.config();
        if (!asBoolean(config.get("collaborationEnabled"))) {
            throw new ServiceException("实时协作当前未启用");
        }
        accessService.requireFeature(userId, workspace.getProjectId(), "canvas.collaborate");
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String ticket = UUID.randomUUID() + "." + UUID.randomUUID();
        TicketPayload payload = new TicketPayload();
        payload.setSessionId(sessionId);
        payload.setWorkspaceId(workspace.getId());
        payload.setProjectId(workspace.getProjectId());
        payload.setUserId(userId);
        payload.setPageId(request.getPageId());
        redisCache.setCacheObject(ticketKey(ticket), payload, TICKET_TTL_SECONDS, TimeUnit.SECONDS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("ticket", ticket);
        result.put("expireAt", Date.from(Instant.now().plusSeconds(TICKET_TTL_SECONDS)));
        result.put("websocketPath", "/ws/canvas?ticket=" + ticket);
        result.put("lockLeaseSeconds", asInt(config.get("lockLeaseSeconds"), 30));
        result.put("lockRenewSeconds", asInt(config.get("lockRenewSeconds"), 10));
        result.put("textMergeMillis", asInt(config.get("textMergeMillis"), 300));
        result.put("heartbeatSeconds", 15);
        Map<String, Object> effective = accessService.effectiveEntitlements(userId, workspace.getProjectId());
        result.put("runtimeConfig", effective.get("runtimeConfig"));
        result.put("effectiveLimits", effective.get("effectiveLimits"));
        return result;
    }

    @Override
    public Map<String, Object> state(Long workspaceId, Long userId) {
        Workspace workspace = accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR", "VIEWER");
        Map<Long, Map<String, Object>> identities = new LinkedHashMap<>();
        for (Map<String, Object> member : canvasMapper.selectMembers(workspace.getProjectId())) {
            Long memberUserId = asLong(member.get("userId"));
            if (memberUserId != null) identities.put(memberUserId, member);
        }

        List<Map<String, Object>> sessionViews = new ArrayList<>();
        Map<Long, Map<String, Object>> onlineUsers = new LinkedHashMap<>();
        for (Map<String, Object> session : listSessions(workspace.getProjectId(), null)) {
            if (!Objects.equals(workspaceId, asLong(session.get("workspaceId")))) continue;
            Long sessionUserId = asLong(session.get("userId"));
            if (sessionUserId == null) continue;
            Map<String, Object> identity = identities.get(sessionUserId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("sessionId", session.get("sessionId"));
            view.put("userId", sessionUserId);
            view.put("nickName", identity == null ? null : identity.get("nickName"));
            view.put("avatarUrl", identity == null ? null : identity.get("avatarUrl"));
            view.put("pageId", session.get("pageId"));
            view.put("connectedAt", session.get("connectedAt"));
            view.put("lastHeartbeatAt", session.get("lastHeartbeatAt"));
            sessionViews.add(view);

            Map<String, Object> online = onlineUsers.computeIfAbsent(sessionUserId, ignored -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("userId", sessionUserId);
                item.put("nickName", identity == null ? null : identity.get("nickName"));
                item.put("avatarUrl", identity == null ? null : identity.get("avatarUrl"));
                item.put("sessionCount", 0);
                item.put("lastHeartbeatAt", session.get("lastHeartbeatAt"));
                return item;
            });
            online.put("sessionCount", ((Number) online.get("sessionCount")).intValue() + 1);
            if (asMillis(session.get("lastHeartbeatAt")) > asMillis(online.get("lastHeartbeatAt"))) {
                online.put("lastHeartbeatAt", session.get("lastHeartbeatAt"));
            }
        }

        List<Map<String, Object>> lockViews = new ArrayList<>();
        for (Map<String, Object> lock : listLocks(workspace.getProjectId(), workspaceId, null)) {
            Long lockUserId = asLong(lock.get("userId"));
            Map<String, Object> identity = identities.get(lockUserId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("objectId", lock.get("objectId"));
            view.put("lockType", lock.get("lockType"));
            view.put("sessionId", lock.get("sessionId"));
            view.put("taskId", lock.get("taskId"));
            view.put("runId", lock.get("runId"));
            view.put("userId", lockUserId);
            view.put("nickName", identity == null ? null : identity.get("nickName"));
            view.put("avatarUrl", identity == null ? null : identity.get("avatarUrl"));
            view.put("acquiredAt", lock.get("acquiredAt"));
            view.put("expireAt", lock.get("expireAt"));
            lockViews.add(view);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspaceId);
        result.put("serverTime", new Date());
        result.put("onlineUsers", new ArrayList<>(onlineUsers.values()));
        result.put("sessions", sessionViews);
        result.put("locks", lockViews);
        return result;
    }

    @Override
    public SessionView consumeTicket(String ticket, String clientIp, Consumer<String> outbound) {
        if (ticket == null || ticket.length() > 160) throw new ServiceException("协作票据无效");
        String key = ticketKey(ticket);
        TicketPayload payload = redisCache.getCacheObject(key, TicketPayload.class);
        if (payload == null || !redisCache.deleteObject(key)) throw new ServiceException("协作票据无效或已使用");
        Workspace workspace = accessService.requireWorkspace(payload.getWorkspaceId(), payload.getUserId(), "OWNER", "EDITOR", "VIEWER");
        Map<String, Object> config = accessService.config();
        if (!asBoolean(config.get("collaborationEnabled"))) throw new ServiceException("实时协作当前未启用");
        accessService.requireFeature(payload.getUserId(), workspace.getProjectId(), "canvas.collaborate");
        LiveSession live;
        RLock admissionLock = redissonClient.getLock("canvas:admission");
        admissionLock.lock();
        try {
            int maxConnections = asInt(config.get("maxConnectionsPerUser"), 3);
            List<SessionMetadata> active = activeSessionMetadata();
            long userConnections = active.stream().filter(s -> Objects.equals(s.userId, payload.getUserId())).count();
            if (userConnections >= maxConnections) throw new ServiceException("当前账号的画布连接数已达上限");
            int configuredOnline = asInt(config.get("maxOnlineEditors"), 10);
            Long capacitySubjectId = entitlementSubjectResolver.resolve(EntitlementSubjectResolver.Strategy.PROJECT_OWNER,
                    payload.getUserId(), workspaceOwner(payload.getProjectId()), null).id();
            int ownerOnline = accessService.entitlementLimit(capacitySubjectId, null,
                    "canvas.online.max", configuredOnline);
            int maxOnline = Math.min(configuredOnline, ownerOnline);
            long onlineUsers = active.stream().filter(s -> Objects.equals(s.workspaceId, payload.getWorkspaceId()))
                    .map(s -> s.userId).distinct().count();
            boolean alreadyOnline = active.stream().anyMatch(s -> Objects.equals(s.workspaceId, payload.getWorkspaceId())
                    && Objects.equals(s.userId, payload.getUserId()));
            if (!alreadyOnline && (maxOnline <= 0 || onlineUsers >= maxOnline)) throw new ServiceException("当前画布在线协作人数已达上限");
            live = new LiveSession(payload.getSessionId(), payload.getWorkspaceId(), payload.getProjectId(),
                    payload.getUserId(), payload.getPageId(), clientIp, outbound);
            sessions.put(live.sessionId, live);
            storeSession(live);
        } finally {
            if (admissionLock.isHeldByCurrentThread()) admissionLock.unlock();
        }
        publishPresence(live.workspaceId, "ONLINE", live.userId, live.sessionId);
        return new SessionView(live.sessionId, live.workspaceId, live.projectId, live.userId, live.pageId);
    }

    @Override
    public void heartbeat(String sessionId, Long acknowledgedSeq) {
        LiveSession session = sessions.get(sessionId);
        if (session == null) throw new ServiceException("协作会话已失效");
        if (System.currentTimeMillis() - session.lastAuthorizationAt > 30_000L) {
            Workspace workspace = accessService.requireWorkspace(session.workspaceId, session.userId,
                    "OWNER", "EDITOR", "VIEWER");
            if (!asBoolean(accessService.config().get("collaborationEnabled"))) {
                throw new ServiceException("实时协作当前未启用");
            }
            accessService.requireFeature(session.userId, workspace.getProjectId(), "canvas.collaborate");
            session.lastAuthorizationAt = System.currentTimeMillis();
        }
        session.lastHeartbeatAt = System.currentTimeMillis();
        if (acknowledgedSeq != null && acknowledgedSeq >= 0) session.acknowledgedSeq = acknowledgedSeq;
        storeSession(session);
    }

    @Override
    public void closeSession(String sessionId, String reason) {
        LiveSession session = sessions.remove(sessionId);
        if (session == null) return;
        redisCache.deleteObject(sessionKey(sessionId));
        releaseSessionEditLocks(session);
        publishPresence(session.workspaceId, "OFFLINE", session.userId, session.sessionId);
        try { session.outbound.accept(JSON.toJSONString(Map.of("type", "SESSION_CLOSED", "reason", safeReason(reason)))); }
        catch (Exception ignored) { }
    }

    @Override
    public List<Map<String, Object>> acquire(CanvasRequests.LockAcquire request, Long userId) {
        Workspace workspace = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        LiveSession session = requireLiveSession(request.getSessionId(), workspace, userId);
        List<String> objectIds = normalizedIds(request.getObjectIds());
        int lease = asInt(accessService.config().get("lockLeaseSeconds"), 30);
        List<LockGrant> acquired = new ArrayList<>();
        List<LockGrant> granted = new ArrayList<>();
        List<LockGrant> reused = new ArrayList<>();
        try {
            for (String objectId : objectIds) {
                if (canvasMapper.selectActiveTaskBindingByObject(workspace.getId(), objectId) != null) {
                    throw new ServiceException("节点存在未完成的生成任务: " + objectId);
                }
                RLock issueLock = redissonClient.getLock("canvas:fence-issue:" + workspace.getId() + ":" + objectId);
                issueLock.lock();
                try {
                    if (hasActiveRuntime(workspace.getId(), objectId, null)) {
                        throw new ServiceException("节点存在未结束的 Agent 或 Skill 运行: " + objectId);
                    }
                    Long persistedFence = canvasMapper.selectObjectFence(workspace.getId(), objectId);
                    String objectLockKey = lockKey(workspace.getId(), objectId);
                    String existing = redisCache.getCacheObject(objectLockKey, String.class);
                    String[] owner = existing == null ? new String[0] : existing.split("\\|", -1);
                    if (owner.length == 6 && "EDIT".equals(owner[0])
                            && Objects.equals(session.sessionId, owner[1])
                            && Objects.equals(userId, parseLong(owner[2]))) {
                        Long existingFence = parseLong(owner[5]);
                        if (!Objects.equals(existingFence, persistedFence) || owner[4].isBlank()) {
                            throw new ServiceException("节点占用已变化，请重新申请: " + objectId);
                        }
                        if (!redisCache.expireIfValueEquals(objectLockKey, existing, lease)) {
                            throw new ServiceException("节点占用已变化，请重新申请: " + objectId);
                        }
                        LockGrant grant = new LockGrant(objectId, owner[4], existing, existingFence);
                        granted.add(grant);
                        reused.add(grant);
                        continue;
                    }
                    if (Objects.equals(persistedFence, Long.MAX_VALUE)) {
                        throw new ServiceException("节点租约序号已达上限");
                    }
                    long fence = persistedFence == null ? 1L : persistedFence + 1L;
                    String token = UUID.randomUUID().toString().replace("-", "");
                    String value = editValue(session.sessionId, userId, token, System.currentTimeMillis(), fence);
                    boolean lockCreated = redisCache.setCacheObjectIfAbsent(objectLockKey, value, lease, TimeUnit.SECONDS);
                    if (!lockCreated && clearTerminalTaskLock(workspace.getId(), objectId, objectLockKey)) {
                        lockCreated = redisCache.setCacheObjectIfAbsent(objectLockKey, value, lease, TimeUnit.SECONDS);
                    }
                    if (!lockCreated) {
                        String holder = redisCache.getCacheObject(objectLockKey, String.class);
                        String[] holderParts = holder == null ? new String[0] : holder.split("\\|", -1);
                        if (holderParts.length >= 3 && "EDIT".equals(holderParts[0])
                                && Objects.equals(userId, parseLong(holderParts[2]))) {
                            throw new ServiceException("节点正在当前账号的另一画布会话中操作: " + objectId);
                        }
                        throw new ServiceException("节点正在被其他成员操作: " + objectId);
                    }
                    LockGrant grant = new LockGrant(objectId, token, value, fence);
                    acquired.add(grant);
                    granted.add(grant);
                    if (canvasMapper.upsertObjectFence(workspace.getId(), objectId, fence) <= 0) {
                        throw new ServiceException("节点租约序号登记失败");
                    }
                } finally {
                    if (issueLock.isHeldByCurrentThread()) issueLock.unlock();
                }
            }
        } catch (RuntimeException e) {
            acquired.forEach(lock -> redisCache.deleteObjectIfValueEquals(lockKey(workspace.getId(), lock.objectId), lock.redisValue));
            throw e;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Date expireAt = Date.from(Instant.now().plusSeconds(lease));
        for (LockGrant grant : granted) {
            result.add(Map.of("objectId", grant.objectId, "lockToken", grant.token, "lockFence", grant.fence,
                    "expireAt", expireAt));
        }
        publishLockChange(workspace.getId(), "ACQUIRED", acquired.stream()
                .map(grant -> lockEventItem(grant.objectId, "EDIT", userId, session.sessionId, null, expireAt))
                .toList());
        publishLockChange(workspace.getId(), "RENEWED", reused.stream()
                .map(grant -> lockEventItem(grant.objectId, "EDIT", userId, session.sessionId, null, expireAt))
                .toList());
        return result;
    }

    @Override
    public List<Map<String, Object>> renew(CanvasRequests.LockChange request, Long userId) {
        Workspace workspace = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        requireLiveSession(request.getSessionId(), workspace, userId);
        int lease = asInt(accessService.config().get("lockLeaseSeconds"), 30);
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            for (CanvasRequests.LockItem item : request.getLocks()) {
                String key = lockKey(workspace.getId(), item.getObjectId());
                String value = redisCache.getCacheObject(key, String.class);
                assertEditValue(value, request.getSessionId(), userId, item.getLockToken());
                if (!redisCache.expireIfValueEquals(key, value, lease)) throw new ServiceException("节点占用已变化，请重新申请");
                Date expireAt = Date.from(Instant.now().plusSeconds(lease));
                result.add(Map.of("objectId", item.getObjectId(), "lockToken", item.getLockToken(),
                        "expireAt", expireAt));
                events.add(lockEventItem(item.getObjectId(), "EDIT", userId, request.getSessionId(), null, expireAt));
            }
        } finally {
            publishLockChange(workspace.getId(), "RENEWED", events);
        }
        return result;
    }

    @Override
    public void release(CanvasRequests.LockChange request, Long userId) {
        Workspace workspace = accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        requireLiveSession(request.getSessionId(), workspace, userId);
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            for (CanvasRequests.LockItem item : request.getLocks()) {
                String key = lockKey(workspace.getId(), item.getObjectId());
                String value = redisCache.getCacheObject(key, String.class);
                assertEditValue(value, request.getSessionId(), userId, item.getLockToken());
                if (!redisCache.deleteObjectIfValueEquals(key, value)) throw new ServiceException("节点占用已变化，请刷新");
                events.add(lockEventItem(item.getObjectId(), "EDIT", userId, request.getSessionId(), null, null));
            }
        } finally {
            publishLockChange(workspace.getId(), "RELEASED", events);
        }
    }

    @Override
    public void assertEditLock(Long workspaceId, String objectId, String sessionId, String lockToken, Long userId) {
        requireEditFence(workspaceId, objectId, sessionId, lockToken, userId);
    }

    @Override
    public void requireEditSession(Long workspaceId, String sessionId, Long userId) {
        Workspace workspace = accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        requireLiveSession(sessionId, workspace, userId);
    }

    @Override
    public long requireEditFence(Long workspaceId, String objectId, String sessionId, String lockToken, Long userId) {
        Workspace workspace = accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        requireLiveSession(sessionId, workspace, userId);
        String value = redisCache.getCacheObject(lockKey(workspaceId, objectId), String.class);
        assertEditValue(value, sessionId, userId, lockToken);
        String[] parts = value.split("\\|", -1);
        Long fence = parts.length >= 6 ? parseLong(parts[5]) : null;
        Long issued = canvasMapper.selectObjectFenceForUpdate(workspaceId, objectId);
        if (fence == null || !Objects.equals(fence, issued)) throw new ServiceException("节点占用已被更新，请重新申请");
        String currentValue = redisCache.getCacheObject(lockKey(workspaceId, objectId), String.class);
        if (!Objects.equals(value, currentValue)) throw new ServiceException("节点占用已变化，请重新提交");
        if (canvasMapper.selectActiveTaskBindingByObject(workspaceId, objectId) != null) {
            throw new ServiceException("节点存在未完成的生成任务");
        }
        if (hasActiveRuntime(workspaceId, objectId, null)) {
            throw new ServiceException("节点存在未结束的 Agent 或 Skill 运行");
        }
        return fence;
    }

    @Override
    public void beginRuntimeLock(Long workspaceId, String objectId, String sessionId, String editToken,
                                 Long userId, Long runId) {
        Workspace workspace = accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        requireLiveSession(sessionId, workspace, userId);
        String key = lockKey(workspaceId, objectId);
        String oldValue = redisCache.getCacheObject(key, String.class);
        assertEditValue(oldValue, sessionId, userId, editToken);
        String[] parts = oldValue.split("\\|", -1);
        Long issuedFence = canvasMapper.selectObjectFenceForUpdate(workspaceId, objectId);
        if (!Objects.equals(parseLong(parts[5]), issuedFence)) throw new ServiceException("节点占用已过期");
        if (canvasMapper.selectActiveTaskBindingByObject(workspaceId, objectId) != null
                || hasActiveRuntime(workspaceId, objectId, runId)) {
            throw new ServiceException("节点已有未结束的生成或运行");
        }
        String runValue = runtimeValue(runId, userId, System.currentTimeMillis());
        if (!redisCache.compareAndSetCacheObject(key, oldValue, runValue, TASK_LOCK_TTL_SECONDS)) {
            throw new ServiceException("节点占用已变化");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) redisCache.deleteObjectIfValueEquals(key, runValue);
                }
                @Override public void afterCommit() { publishRuntimeLock(workspaceId, objectId, userId, runId, "RUN_RESERVED"); }
            });
        } else publishRuntimeLock(workspaceId, objectId, userId, runId, "RUN_RESERVED");
    }

    @Override
    public void renewRuntimeLock(Long workspaceId, String objectId, Long userId, Long runId) {
        String key = lockKey(workspaceId, objectId);
        String value = redisCache.getCacheObject(key, String.class);
        String prefix = "RUN|" + runId + "|" + userId + "|";
        if (value == null) {
            if (!redisCache.setCacheObjectIfAbsent(key, runtimeValue(runId, userId, System.currentTimeMillis()),
                    TASK_LOCK_TTL_SECONDS, TimeUnit.SECONDS)) throw new ServiceException("节点占用已变化");
        } else if (!value.startsWith(prefix) || !redisCache.expireIfValueEquals(key, value, TASK_LOCK_TTL_SECONDS)) {
            throw new ServiceException("节点占用已变化");
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publishRuntimeLock(workspaceId, objectId, userId, runId, "RUN_RENEWED"); }
            });
        } else publishRuntimeLock(workspaceId, objectId, userId, runId, "RUN_RENEWED");
    }

    @Override
    public void releaseRuntimeLock(Long workspaceId, String objectId, Long userId, Long runId) {
        Runnable release = () -> {
            String key = lockKey(workspaceId, objectId);
            String value = redisCache.getCacheObject(key, String.class);
            if (value != null && value.startsWith("RUN|" + runId + "|" + userId + "|")) {
                redisCache.deleteObjectIfValueEquals(key, value);
            }
            publishRuntimeLock(workspaceId, objectId, userId, runId, "RUN_RELEASED");
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { release.run(); }
            });
        } else release.run();
    }

    @Override
    public long beginAssistantRuntimeLock(Long workspaceId, String objectId, Long expectedVersion,
                                          Long userId, Long runId, Long runtimeFencingToken) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        CanvasObject node = canvasMapper.selectCanvasObject(workspaceId, objectId);
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), expectedVersion)) {
            throw assistantFailure("CANVAS_REVISION_STALE");
        }
        RLock issueLock = redissonClient.getLock("canvas:fence-issue:" + workspaceId + ":" + objectId);
        issueLock.lock();
        try {
            String key = lockKey(workspaceId, objectId);
            Long persistedFence = canvasMapper.selectObjectFence(workspaceId, objectId);
            String existing = redisCache.getCacheObject(key, String.class);
            String identityPrefix = assistantPrefix(runId, userId, runtimeFencingToken);
            if (existing != null && existing.startsWith(identityPrefix)) {
                Long nodeFence = assistantNodeFence(existing);
                if (nodeFence == null || !Objects.equals(nodeFence, persistedFence)
                        || !redisCache.expireIfValueEquals(key, existing, TASK_LOCK_TTL_SECONDS)) {
                    throw assistantFailure("CANVAS_OCCUPANCY_STALE");
                }
                return nodeFence;
            }
            if (existing != null && clearTerminalTaskLock(workspaceId, objectId, key)) {
                existing = null;
            }
            if (existing != null || canvasMapper.selectActiveTaskBindingByObject(workspaceId, objectId) != null
                    || hasActiveRuntime(workspaceId, objectId, runId)) {
                throw assistantFailure("CANVAS_OCCUPIED");
            }
            if (Objects.equals(persistedFence, Long.MAX_VALUE)) {
                throw assistantFailure("CANVAS_FENCE_EXHAUSTED");
            }
            long nodeFence = persistedFence == null ? 1L : persistedFence + 1L;
            String value = assistantValue(runId, userId, runtimeFencingToken, nodeFence,
                    System.currentTimeMillis());
            if (!redisCache.setCacheObjectIfAbsent(key, value, TASK_LOCK_TTL_SECONDS, TimeUnit.SECONDS)) {
                throw assistantFailure("CANVAS_OCCUPANCY_CHANGED");
            }
            try {
                if (canvasMapper.upsertObjectFence(workspaceId, objectId, nodeFence) <= 0) {
                    throw assistantFailure("CANVAS_FENCE_PERSIST_FAILED");
                }
            } catch (RuntimeException error) {
                redisCache.deleteObjectIfValueEquals(key, value);
                throw error;
            }
            publishAssistantLock(workspaceId, objectId, userId, runId, runtimeFencingToken,
                    "ASSISTANT_RESERVED");
            return nodeFence;
        } finally {
            if (issueLock.isHeldByCurrentThread()) issueLock.unlock();
        }
    }

    @Override
    public long requireAssistantRuntimeFence(Long workspaceId, String objectId, Long expectedVersion,
                                              Long userId, Long runId, Long runtimeFencingToken) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        CanvasObject node = canvasMapper.selectCanvasObjectForUpdate(workspaceId, objectId);
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), expectedVersion)) {
            throw assistantFailure("CANVAS_REVISION_STALE");
        }
        String key = lockKey(workspaceId, objectId);
        String value = redisCache.getCacheObject(key, String.class);
        if (value == null || !value.startsWith(assistantPrefix(runId, userId, runtimeFencingToken))) {
            throw assistantFailure("CANVAS_OCCUPANCY_MISSING");
        }
        Long nodeFence = assistantNodeFence(value);
        Long persistedFence = canvasMapper.selectObjectFenceForUpdate(workspaceId, objectId);
        if (nodeFence == null || !Objects.equals(nodeFence, persistedFence)
                || !Objects.equals(value, redisCache.getCacheObject(key, String.class))) {
            throw assistantFailure("CANVAS_OCCUPANCY_STALE");
        }
        if (canvasMapper.selectActiveTaskBindingByObject(workspaceId, objectId) != null
                || hasActiveRuntime(workspaceId, objectId, runId)) {
            throw assistantFailure("CANVAS_ACTIVE_WORK_CONFLICT");
        }
        return nodeFence;
    }

    @Override
    public long requireAssistantRuntimeFenceForMutation(Long workspaceId, String objectId, Long expectedVersion,
                                                        Long expectedNodeFence, Long userId, Long runId) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        CanvasObject node = canvasMapper.selectCanvasObjectForUpdate(workspaceId, objectId);
        if (node == null || !"NODE".equals(node.getObjectType()) || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), expectedVersion)) {
            throw assistantFailure("CANVAS_REVISION_STALE");
        }
        Long persistedFence = canvasMapper.selectObjectFenceForUpdate(workspaceId, objectId);
        if (expectedNodeFence == null || expectedNodeFence < 1
                || !Objects.equals(expectedNodeFence, persistedFence)) {
            throw assistantFailure("CANVAS_OCCUPANCY_STALE");
        }
        if (canvasMapper.selectActiveTaskBindingByObject(workspaceId, objectId) != null
                || hasActiveRuntime(workspaceId, objectId, runId)) {
            throw assistantFailure("CANVAS_ACTIVE_WORK_CONFLICT");
        }
        return persistedFence;
    }

    @Override
    public void renewAssistantRuntimeLock(Long workspaceId, String objectId, Long expectedVersion,
                                          Long userId, Long runId, Long runtimeFencingToken) {
        long nodeFence = requireAssistantRuntimeFence(workspaceId, objectId, expectedVersion, userId, runId,
                runtimeFencingToken);
        String key = lockKey(workspaceId, objectId);
        String value = redisCache.getCacheObject(key, String.class);
        if (value == null || !value.startsWith(assistantPrefix(runId, userId, runtimeFencingToken))
                || !Objects.equals(nodeFence, assistantNodeFence(value))
                || !redisCache.expireIfValueEquals(key, value, TASK_LOCK_TTL_SECONDS)) {
            throw assistantFailure("CANVAS_RENEW_FAILED");
        }
        publishAssistantLock(workspaceId, objectId, userId, runId, runtimeFencingToken,
                "ASSISTANT_RENEWED");
    }

    @Override
    public void releaseAssistantRuntimeLock(Long workspaceId, String objectId, Long userId,
                                            Long runId, Long runtimeFencingToken) {
        String key = lockKey(workspaceId, objectId);
        String value = redisCache.getCacheObject(key, String.class);
        if (value != null && value.startsWith(assistantPrefix(runId, userId, runtimeFencingToken))
                && redisCache.deleteObjectIfValueEquals(key, value)) {
            publishAssistantLock(workspaceId, objectId, userId, runId, runtimeFencingToken,
                    "ASSISTANT_RELEASED");
        }
    }

    @Override
    public void beginTaskLock(Long workspaceId, String objectId, String sessionId, String editToken,
                              Long userId, String operationId) {
        String key = lockKey(workspaceId, objectId);
        String oldValue = redisCache.getCacheObject(key, String.class);
        assertEditValue(oldValue, sessionId, userId, editToken);
        String pendingValue = pendingTaskValue(operationId, userId, System.currentTimeMillis());
        if (!redisCache.compareAndSetCacheObject(key, oldValue, pendingValue, PENDING_TASK_LOCK_TTL_SECONDS)) {
            throw new ServiceException("节点占用已变化，生成任务未绑定");
        }
        publishLockChange(workspaceId, "TASK_PENDING", List.of(lockEventItem(objectId, "TASK", userId,
                sessionId, null, Date.from(Instant.now().plusSeconds(PENDING_TASK_LOCK_TTL_SECONDS)))));
    }

    @Override
    public void requireAssistantMediaTaskLock(Long workspaceId, String objectId, Long expectedVersion,
                                              Long userId, String operationId, String callId, Long taskId) {
        TaskBinding binding = canvasMapper.selectTaskBindingByOperation(workspaceId, operationId);
        CanvasObject node = canvasMapper.selectCanvasObjectForUpdate(workspaceId, objectId);
        if (binding == null || !Objects.equals(binding.getObjectId(), objectId)
                || !Objects.equals(binding.getActorUserId(), userId)
                || !Objects.equals(binding.getInputObjectVersion(), expectedVersion)
                || !Objects.equals(binding.getCallId(), callId)
                || taskId == null || taskId < 1
                || !(binding.getTaskId() == null && "SUBMITTING".equals(binding.getStatus())
                    || Objects.equals(binding.getTaskId(), taskId))
                || TERMINAL_TASK_STATUS.contains(binding.getStatus())
                || node == null || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), expectedVersion)) {
            throw assistantFailure("CANVAS_TASK_SUBMISSION_SCOPE_CHANGED");
        }
        String key = lockKey(workspaceId, objectId);
        String value = redisCache.getCacheObject(key, String.class);
        if (value == null && Objects.equals(binding.getTaskId(), taskId)) {
            renewTaskLock(workspaceId, objectId, taskId);
            value = redisCache.getCacheObject(key, String.class);
        }
        String pendingPrefix = "TASK_PENDING|" + operationId + "|" + userId + "|";
        String taskPrefix = "TASK|" + taskId + "|" + userId + "|";
        // The provider guard has already verified this exact persisted task and submission proof.
        // Its database snapshot can still see SUBMITTING while the caller has bound the Redis lease.
        // Also allow one re-read when that same pending lease changes to the bound task during CAS.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (value == null || !(value.startsWith(pendingPrefix) || value.startsWith(taskPrefix))) {
                break;
            }
            if (redisCache.expireIfValueEquals(key, value,
                    value.startsWith(pendingPrefix) ? PENDING_TASK_LOCK_TTL_SECONDS : TASK_LOCK_TTL_SECONDS)) {
                return;
            }
            if (attempt == 0) {
                value = redisCache.getCacheObject(key, String.class);
            }
        }
        throw assistantFailure("CANVAS_TASK_SUBMISSION_LOCK_CHANGED");
    }

    @Override
    public void beginAssistantTaskLock(Long workspaceId, String objectId, Long expectedVersion,
                                       Long userId, Long runId, Long runtimeFencingToken, Long nodeFence,
                                       String operationId, String callId) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        TaskBinding binding = canvasMapper.selectTaskBindingByOperation(workspaceId, operationId);
        CanvasObject node = canvasMapper.selectCanvasObject(workspaceId, objectId);
        if (binding == null || !Objects.equals(binding.getObjectId(), objectId)
                || !Objects.equals(binding.getActorUserId(), userId)
                || !Objects.equals(binding.getInputObjectVersion(), expectedVersion)
                || !Objects.equals(binding.getCallId(), callId)
                || binding.getTaskId() != null || !"SUBMITTING".equals(binding.getStatus())
                || node == null || !Objects.equals(node.getDeleted(), 0)
                || !Objects.equals(node.getObjectVersion(), expectedVersion)
                || nodeFence == null || nodeFence < 1
                || !Objects.equals(nodeFence, canvasMapper.selectObjectFence(workspaceId, objectId))) {
            throw assistantFailure("CANVAS_TASK_INTENT_SCOPE_CHANGED");
        }
        String key = lockKey(workspaceId, objectId);
        String oldValue = redisCache.getCacheObject(key, String.class);
        String pendingPrefix = "TASK_PENDING|" + operationId + "|" + userId + "|";
        if (oldValue != null && oldValue.startsWith(pendingPrefix)) {
            if (!redisCache.expireIfValueEquals(key, oldValue, PENDING_TASK_LOCK_TTL_SECONDS)) {
                throw assistantFailure("CANVAS_TASK_INTENT_LOCK_CHANGED");
            }
            return;
        }
        if (oldValue == null || !oldValue.startsWith(assistantPrefix(runId, userId, runtimeFencingToken))
                || !Objects.equals(nodeFence, assistantNodeFence(oldValue))) {
            throw assistantFailure("CANVAS_TASK_INTENT_LOCK_CHANGED");
        }
        String pendingValue = pendingTaskValue(operationId, userId, System.currentTimeMillis());
        if (!redisCache.compareAndSetCacheObject(key, oldValue, pendingValue, PENDING_TASK_LOCK_TTL_SECONDS)) {
            throw assistantFailure("CANVAS_TASK_INTENT_LOCK_CHANGED");
        }
        publishLockChange(workspaceId, "TASK_PENDING", List.of(lockEventItem(objectId, "TASK", userId,
                null, null, Date.from(Instant.now().plusSeconds(PENDING_TASK_LOCK_TTL_SECONDS)))));
    }

    @Override
    public void bindTaskLock(Long workspaceId, String objectId, Long userId, String operationId, Long taskId) {
        String key = lockKey(workspaceId, objectId);
        String pending = redisCache.getCacheObject(key, String.class);
        String expectedPrefix = "TASK_PENDING|" + operationId + "|" + userId + "|";
        String taskValue = taskValue(taskId, userId, System.currentTimeMillis());
        if (pending != null && pending.startsWith(expectedPrefix)) {
            if (!redisCache.compareAndSetCacheObject(key, pending, taskValue, TASK_LOCK_TTL_SECONDS)) {
                throw new ServiceException("生成占用已变化");
            }
            publishLockChange(workspaceId, "TASK_BOUND", List.of(lockEventItem(objectId, "TASK", userId,
                    null, taskId, Date.from(Instant.now().plusSeconds(TASK_LOCK_TTL_SECONDS)))));
            return;
        }
        TaskBinding binding = canvasMapper.selectTaskBinding(taskId);
        if (binding == null || !Objects.equals(binding.getWorkspaceId(), workspaceId)
                || !Objects.equals(binding.getObjectId(), objectId) || !Objects.equals(binding.getActorUserId(), userId)
                || !Objects.equals(binding.getOperationId(), operationId)) {
            throw new ServiceException("生成占用已失效");
        }
        if (TERMINAL_TASK_STATUS.contains(binding.getStatus())) return;
        if (!redisCache.setCacheObjectIfAbsent(key, taskValue, TASK_LOCK_TTL_SECONDS, TimeUnit.SECONDS)) {
            String existing = redisCache.getCacheObject(key, String.class);
            if (existing == null || !existing.startsWith("TASK|" + taskId + "|")) throw new ServiceException("节点已被新的操作占用");
        }
        publishLockChange(workspaceId, "TASK_BOUND", List.of(lockEventItem(objectId, "TASK", userId,
                null, taskId, Date.from(Instant.now().plusSeconds(TASK_LOCK_TTL_SECONDS)))));
    }

    @Override
    public void releasePendingTaskLock(Long workspaceId, String objectId, Long userId, String operationId) {
        String key = lockKey(workspaceId, objectId);
        String value = redisCache.getCacheObject(key, String.class);
        if (value != null && value.startsWith("TASK_PENDING|" + operationId + "|" + userId + "|")) {
            if (redisCache.deleteObjectIfValueEquals(key, value)) {
                publishLockChange(workspaceId, "TASK_RELEASED",
                        List.of(lockEventItem(objectId, "TASK", userId, null, null, null)));
            }
        }
    }

    @Override
    public void releaseTaskLock(Long workspaceId, String objectId, Long taskId) {
        String key = lockKey(workspaceId, objectId);
        String value = redisCache.getCacheObject(key, String.class);
        if (value != null && value.startsWith("TASK|" + taskId + "|")) {
            Long lockUserId = lockUserId(value);
            if (redisCache.deleteObjectIfValueEquals(key, value)) {
                publishLockChange(workspaceId, "TASK_RELEASED",
                        List.of(lockEventItem(objectId, "TASK", lockUserId, null, taskId, null)));
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renewTaskLock(Long workspaceId, String objectId, Long taskId) {
        // 使用当前读并锁住权威绑定，避免终态提交与缺失租约重建竞争。
        TaskBinding binding = canvasMapper.selectActiveTaskBindingByObject(workspaceId, objectId);
        if (binding == null || !Objects.equals(binding.getTaskId(), taskId)) return;
        String key = lockKey(workspaceId, objectId);
        String value = redisCache.getCacheObject(key, String.class);
        if (value == null) {
            String recovered = taskValue(taskId, binding.getActorUserId(), System.currentTimeMillis());
            if (redisCache.setCacheObjectIfAbsent(key, recovered, TASK_LOCK_TTL_SECONDS, TimeUnit.SECONDS)) {
                value = recovered;
            } else {
                value = redisCache.getCacheObject(key, String.class);
            }
        }
        if (value != null && value.startsWith("TASK|" + taskId + "|")) {
            if (redisCache.expireIfValueEquals(key, value, TASK_LOCK_TTL_SECONDS)) {
                publishLockChange(workspaceId, "TASK_RENEWED", List.of(lockEventItem(objectId, "TASK",
                        lockUserId(value), null, taskId, Date.from(Instant.now().plusSeconds(TASK_LOCK_TTL_SECONDS)))));
            }
        }
    }

    @Override
    public void publishWorkspaceEvent(Long workspaceId, String eventJson) {
        deliverLocal(workspaceId, null, eventJson);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("origin", instanceId);
        envelope.put("workspaceId", workspaceId);
        envelope.put("event", eventJson);
        redisCache.publishString(EVENT_CHANNEL, JSON.toJSONString(envelope));
    }

    private void deliverLocal(Long workspaceId, String targetSessionId, String eventJson) {
        for (LiveSession session : sessions.values()) {
            if (!Objects.equals(workspaceId, session.workspaceId)) continue;
            if (targetSessionId != null && !Objects.equals(targetSessionId, session.sessionId)) continue;
            try { session.outbound.accept(eventJson); }
            catch (Exception e) { closeSession(session.sessionId, "写入失败"); }
        }
    }

    @Override
    public List<Map<String, Object>> listSessions(Long projectId, Long userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionMetadata session : activeSessionMetadata()) {
            if (projectId != null && !Objects.equals(projectId, session.projectId)) continue;
            if (userId != null && !Objects.equals(userId, session.userId)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", session.sessionId);
            item.put("projectId", session.projectId);
            item.put("workspaceId", session.workspaceId);
            item.put("userId", session.userId);
            item.put("pageId", session.pageId);
            item.put("connectedAt", new Date(session.connectedAt));
            item.put("lastHeartbeatAt", new Date(session.lastHeartbeatAt));
            item.put("clientIp", session.clientIp);
            item.put("status", "ONLINE");
            result.add(item);
        }
        result.sort(Comparator.comparing(v -> Objects.toString(v.get("sessionId"))));
        return result;
    }

    @Override
    public List<Map<String, Object>> listLocks(Long projectId, Long workspaceId, Long userId) {
        String pattern = workspaceId == null ? LOCK_PREFIX + "*" : LOCK_PREFIX + workspaceId + ":*";
        Collection<String> keys = redisCache.scanKeys(pattern, 5_000);
        List<Map<String, Object>> result = new ArrayList<>();
        Map<Long, Workspace> workspaceCache = new LinkedHashMap<>();
        for (String key : keys) {
            String[] keyParts = key.split(":", 4);
            if (keyParts.length != 4) continue;
            Long keyWorkspace;
            try { keyWorkspace = Long.valueOf(keyParts[2]); } catch (NumberFormatException e) { continue; }
            Workspace workspace = workspaceCache.computeIfAbsent(keyWorkspace, canvasMapper::selectWorkspace);
            if (workspace == null) continue;
            if (projectId != null && !Objects.equals(projectId, workspace.getProjectId())) continue;
            if (workspaceId != null && !Objects.equals(workspaceId, keyWorkspace)) continue;
            String value = redisCache.getCacheObject(key, String.class);
            if (value == null) continue;
            String[] parts = value.split("\\|", -1);
            if (parts.length < 4) continue;
            if ("RUN".equals(parts[0])) continue;
            Long lockUser = parseLong(parts[2]);
            if (userId != null && !Objects.equals(userId, lockUser)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", workspace.getProjectId());
            item.put("workspaceId", keyWorkspace);
            item.put("objectId", keyParts[3]);
            item.put("lockType", "TASK_PENDING".equals(parts[0]) ? "TASK"
                    : "ASSISTANT".equals(parts[0]) ? "ASSISTANT_RUNTIME" : parts[0]);
            item.put("sessionId", "EDIT".equals(parts[0]) ? parts[1] : null);
            item.put("taskId", "TASK".equals(parts[0]) ? parseLong(parts[1]) : null);
            item.put("runId", "ASSISTANT".equals(parts[0]) ? parts[1] : null);
            item.put("runtimeFencingToken", "ASSISTANT".equals(parts[0]) && parts.length > 3
                    ? parseLong(parts[3]) : null);
            item.put("nodeFence", "ASSISTANT".equals(parts[0]) && parts.length > 4
                    ? parseLong(parts[4]) : null);
            item.put("userId", lockUser);
            int timeIndex = "ASSISTANT".equals(parts[0]) ? 5 : 3;
            Long acquiredAt = parts.length > timeIndex ? parseLong(parts[timeIndex]) : null;
            item.put("acquiredAt", new Date(acquiredAt == null ? 0L : acquiredAt));
            long ttl = redisCache.getExpire(key);
            item.put("expireAt", ttl > 0 ? Date.from(Instant.now().plusSeconds(ttl)) : null);
            result.add(item);
        }
        LambdaQueryWrapper<AidRuntimeRun> runQuery = new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getDelFlag, "0")
                .in(AidRuntimeRun::getStatus, ACTIVE_RUN_STATUS)
                .apply("(executor_strategy IS NULL OR executor_strategy <> {0})", "ASSISTANT_LOOP")
                .isNotNull(AidRuntimeRun::getWorkspaceId)
                .isNotNull(AidRuntimeRun::getNodeObjectId);
        if (projectId != null) runQuery.eq(AidRuntimeRun::getProjectId, projectId);
        if (workspaceId != null) runQuery.eq(AidRuntimeRun::getWorkspaceId, workspaceId);
        if (userId != null) runQuery.eq(AidRuntimeRun::getUserId, userId);
        for (AidRuntimeRun run : runtimeRunMapper.selectList(runQuery)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", run.getProjectId());
            item.put("workspaceId", run.getWorkspaceId());
            item.put("objectId", run.getNodeObjectId());
            item.put("lockType", "RUNTIME_RUN");
            item.put("sessionId", null);
            item.put("taskId", run.getMediaTaskId());
            item.put("runId", Long.toString(run.getId()));
            item.put("userId", run.getUserId());
            item.put("acquiredAt", run.getCreateTime());
            item.put("expireAt", null);
            result.add(item);
        }
        return result;
    }

    @Override
    public int onlineCount(Long workspaceId) {
        return (int) activeSessionMetadata().stream().filter(s -> Objects.equals(s.workspaceId, workspaceId))
                .map(s -> s.userId).distinct().count();
    }

    @Override
    public boolean terminateSession(String sessionId, String reason) {
        SessionMetadata metadata = redisCache.getCacheObject(sessionKey(sessionId), SessionMetadata.class);
        if (metadata == null) return false;
        LiveSession local = sessions.get(sessionId);
        if (local != null) closeSession(sessionId, reason);
        else {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "SESSION_CLOSED");
            event.put("reason", safeReason(reason));
            publishTarget(metadata.workspaceId, sessionId, JSON.toJSONString(event));
        }
        return true;
    }

    @Override
    public void cleanupIdleSessions() {
        long threshold = System.currentTimeMillis() - SESSION_IDLE_MILLIS;
        new ArrayList<>(sessions.values()).stream().filter(s -> s.lastHeartbeatAt < threshold)
                .forEach(s -> closeSession(s.sessionId, "心跳超时"));
    }

    private LiveSession requireLiveSession(String sessionId, Workspace workspace, Long userId) {
        LiveSession session = sessions.get(sessionId);
        if (session == null || !Objects.equals(session.workspaceId, workspace.getId()) || !Objects.equals(session.userId, userId)) {
            throw new ServiceException("协作会话不存在或与当前用户不匹配");
        }
        return session;
    }

    private void releaseSessionEditLocks(LiveSession session) {
        List<Map<String, Object>> released = new ArrayList<>();
        for (String key : redisCache.scanKeys(LOCK_PREFIX + session.workspaceId + ":*", 5_000)) {
            String value = redisCache.getCacheObject(key, String.class);
            if (value != null && value.startsWith("EDIT|" + session.sessionId + "|")) {
                if (redisCache.deleteObjectIfValueEquals(key, value)) {
                    String[] parts = key.split(":", 4);
                    if (parts.length == 4) released.add(
                            lockEventItem(parts[3], "EDIT", session.userId, session.sessionId, null, null));
                }
            }
        }
        publishLockChange(session.workspaceId, "RELEASED", released);
    }

    private void publishPresence(Long workspaceId, String state, Long userId, String sessionId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "PRESENCE_STATE");
        event.put("state", state);
        event.put("userId", userId);
        event.put("sessionId", sessionId);
        event.put("serverTime", new Date());
        try {
            event.put("onlineCount", onlineCount(workspaceId));
            publishWorkspaceEvent(workspaceId, JSON.toJSONString(event));
        } catch (RuntimeException e) {
            log.warn("画布会话状态已变更，但实时通知发送失败，workspaceId={}", workspaceId, e);
        }
    }

    private void publishLockChange(Long workspaceId, String action, List<Map<String, Object>> locks) {
        if (locks == null || locks.isEmpty()) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "LOCK_CHANGED");
        event.put("action", action);
        event.put("workspaceId", workspaceId);
        event.put("locks", locks);
        event.put("serverTime", new Date());
        try {
            publishWorkspaceEvent(workspaceId, JSON.toJSONString(event));
        } catch (RuntimeException e) {
            log.warn("画布锁状态已变更，但实时通知发送失败，workspaceId={}", workspaceId, e);
        }
    }

    private static Map<String, Object> lockEventItem(String objectId, String lockType, Long userId,
                                                      String sessionId, Long taskId, Date expireAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("objectId", objectId);
        item.put("lockType", lockType);
        item.put("userId", userId);
        item.put("sessionId", sessionId);
        item.put("taskId", taskId);
        item.put("expireAt", expireAt);
        return item;
    }

    private static Long lockUserId(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\|", -1);
        return parts.length > 2 ? parseLong(parts[2]) : null;
    }

    private Long workspaceOwner(Long projectId) {
        AidComicProject project = canvasMapper.selectCanvasProject(projectId);
        if (project == null) throw new ServiceException("项目已失效");
        return project.getUserId();
    }

    private static List<String> normalizedIds(List<String> ids) {
        Set<String> unique = new LinkedHashSet<>(ids);
        if (unique.size() != ids.size()) throw new ServiceException("节点标识不能重复");
        return unique.stream().sorted().toList();
    }

    private static String lockKey(Long workspaceId, String objectId) { return LOCK_PREFIX + workspaceId + ":" + objectId; }
    private static String ticketKey(String ticket) { return TICKET_PREFIX + sha256(ticket); }
    private static String editValue(String sessionId, Long userId, String token, long time, long fence) {
        return "EDIT|" + sessionId + "|" + userId + "|" + time + "|" + token + "|" + fence;
    }
    private static String taskValue(Long taskId, Long userId, long time) { return "TASK|" + taskId + "|" + userId + "|" + time; }
    private static String pendingTaskValue(String operationId, Long userId, long time) {
        return "TASK_PENDING|" + operationId + "|" + userId + "|" + time;
    }

    private static String runtimeValue(Long runId, Long userId, long time) {
        return "RUN|" + runId + "|" + userId + "|" + time;
    }

    private static String assistantValue(Long runId, Long userId, Long runtimeFencingToken,
                                         Long nodeFence, long time) {
        return assistantPrefix(runId, userId, runtimeFencingToken) + nodeFence + "|" + time;
    }

    private static String assistantPrefix(Long runId, Long userId, Long runtimeFencingToken) {
        if (runId == null || userId == null || runtimeFencingToken == null) {
            throw assistantFailure("RUNTIME_IDENTITY_INCOMPLETE");
        }
        return "ASSISTANT|" + runId + "|" + userId + "|" + runtimeFencingToken + "|";
    }

    private static Long assistantNodeFence(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\|", -1);
        return parts.length == 6 && "ASSISTANT".equals(parts[0]) ? parseLong(parts[4]) : null;
    }

    private AidRuntimeRun activeRun(Long workspaceId, String objectId, Long excludedRunId) {
        LambdaQueryWrapper<AidRuntimeRun> query = new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getWorkspaceId, workspaceId)
                .eq(AidRuntimeRun::getNodeObjectId, objectId)
                .eq(AidRuntimeRun::getDelFlag, "0")
                .in(AidRuntimeRun::getStatus, ACTIVE_RUN_STATUS)
                .apply("(executor_strategy IS NULL OR executor_strategy <> {0})", "ASSISTANT_LOOP");
        if (excludedRunId != null) query.ne(AidRuntimeRun::getId, excludedRunId);
        query.last("LIMIT 1");
        return runtimeRunMapper.selectOne(query);
    }

    private boolean hasActiveRuntime(Long workspaceId, String objectId, Long excludedRunId) {
        // Assistant context refs express authorization, not a lifecycle-long edit lock. Assistant writes use the
        // short-lived ASSISTANT Redis fence below, while submitted media tasks keep their independent task binding.
        // This lets a user edit after a question/pause; exact revision CAS then prevents a late assistant overwrite.
        return activeRun(workspaceId, objectId, excludedRunId) != null;
    }

    private void publishRuntimeLock(Long workspaceId, String objectId, Long userId, Long runId, String action) {
        Map<String, Object> item = new LinkedHashMap<>(lockEventItem(objectId, "RUNTIME_RUN", userId,
                null, null, "RUN_RELEASED".equals(action) ? null : Date.from(Instant.now().plusSeconds(TASK_LOCK_TTL_SECONDS))));
        item.put("runId", Long.toString(runId));
        publishLockChange(workspaceId, action, List.of(item));
        AidRuntimeRun run = runtimeRunMapper.selectById(runId);
        if (run == null) return;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "RUNTIME_RUN_STATE");
        state.put("workspaceId", Long.toString(workspaceId));
        state.put("objectId", objectId);
        state.put("runId", Long.toString(runId));
        state.put("inputObjectVersion", run.getNodeVersion());
        state.put("memberType", run.getMemberType());
        state.put("actorUserId", Long.toString(userId));
        state.put("status", run.getStatus());
        state.put("taskId", run.getMediaTaskId() == null ? null : Long.toString(run.getMediaTaskId()));
        state.put("serverTime", new Date());
        publishWorkspaceEvent(workspaceId, JSON.toJSONString(state));
    }

    private void publishAssistantLock(Long workspaceId, String objectId, Long userId, Long runId,
                                      Long runtimeFencingToken, String action) {
        Map<String, Object> event = new LinkedHashMap<>(lockEventItem(objectId, "ASSISTANT_RUNTIME", userId,
                null, null, "ASSISTANT_RELEASED".equals(action) ? null
                        : Date.from(Instant.now().plusSeconds(TASK_LOCK_TTL_SECONDS))));
        event.put("type", "ASSISTANT_RUNTIME_LOCK");
        event.put("action", action);
        event.put("runId", Long.toString(runId));
        event.put("runtimeFencingToken", runtimeFencingToken);
        publishWorkspaceEvent(workspaceId, JSON.toJSONString(event));
    }

    private static void assertEditValue(String value, String sessionId, Long userId, String token) {
        String[] parts = value == null ? new String[0] : value.split("\\|", -1);
        boolean matches = parts.length == 6
                && "EDIT".equals(parts[0])
                && Objects.equals(sessionId, parts[1])
                && Objects.equals(userId, parseLong(parts[2]))
                && parseLong(parts[3]) != null
                && Objects.equals(token, parts[4])
                && parseLong(parts[5]) != null;
        if (!matches) {
            throw new ServiceException("节点未由当前会话占用或占用已过期");
        }
    }

    private boolean clearTerminalTaskLock(Long workspaceId, String objectId, String key) {
        String value = redisCache.getCacheObject(key, String.class);
        if (value == null) return false;
        if (value.startsWith("ASSISTANT|")) {
            String[] parts = value.split("\\|", -1);
            Long runId = parts.length > 1 ? parseLong(parts[1]) : null;
            Long runtimeFence = parts.length > 3 ? parseLong(parts[3]) : null;
            Map<String, Object> scope = runId == null ? null : canvasMapper.selectAssistantRuntimeScope(runId);
            boolean assistantLoop = scope != null && "ASSISTANT_LOOP".equals(scope.get("executorStrategy"));
            // A workspace assistant owns approved child targets, not only the optional input node.
            // Its lease/fence and active session remain authoritative until transfer to TASK_PENDING.
            boolean active = scope != null
                    && Objects.equals(workspaceId, asLong(scope.get("workspaceId")))
                    && Objects.equals(runId, asLong(scope.get("runId")))
                    && Objects.equals(parts.length > 2 ? parseLong(parts[2]) : null, asLong(scope.get("userId")))
                    && Objects.equals(runtimeFence, asLong(scope.get("runtimeFencingToken")))
                    && (assistantLoop
                        ? Objects.equals(runId, asLong(scope.get("activeRuntimeRunId")))
                            && Objects.equals(scope.get("userId"), scope.get("sessionUserId"))
                            && "ASSISTANT".equals(scope.get("sessionType"))
                            && "0".equals(scope.get("delFlag")) && "0".equals(scope.get("sessionDelFlag"))
                            && Boolean.FALSE.equals(scope.get("stopRequested"))
                            && com.aid.canvas.runtime.RuntimeScopeAccess.permitsNewOperation(
                                Objects.toString(scope.get("status"), ""), Objects.toString(scope.get("nextAction"), ""))
                        : Objects.equals(objectId, Objects.toString(scope.get("objectId"), null))
                            && Set.of("RUNNING", "WAITING_TOOL").contains(Objects.toString(scope.get("status"), "")))
                    && asMillis(scope.get("leaseUntil")) > System.currentTimeMillis();
            return !active && redisCache.deleteObjectIfValueEquals(key, value);
        }
        if (value.startsWith("RUN|")) {
            String[] parts = value.split("\\|", -1);
            Long runId = parts.length > 1 ? parseLong(parts[1]) : null;
            AidRuntimeRun run = runId == null ? null : runtimeRunMapper.selectById(runId);
            // A newly reserved run may still be uncommitted in another transaction.
            if (run == null) return false;
            if (Objects.equals(run.getWorkspaceId(), workspaceId)
                    && Objects.equals(run.getNodeObjectId(), objectId)
                    && "0".equals(run.getDelFlag()) && ACTIVE_RUN_STATUS.contains(run.getStatus())) return false;
            return redisCache.deleteObjectIfValueEquals(key, value);
        }
        TaskBinding binding;
        if (value.startsWith("TASK|")) {
            String[] parts = value.split("\\|", -1);
            Long taskId = parts.length > 1 ? parseLong(parts[1]) : null;
            binding = taskId == null ? null : canvasMapper.selectTaskBinding(taskId);
        } else if (value.startsWith("TASK_PENDING|")) {
            String[] parts = value.split("\\|", -1);
            String operationId = parts.length > 1 ? parts[1] : null;
            binding = operationId == null ? null : canvasMapper.selectTaskBindingByOperation(workspaceId, operationId);
        } else {
            return false;
        }
        if (binding == null || !Objects.equals(binding.getWorkspaceId(), workspaceId)
                || !Objects.equals(binding.getObjectId(), objectId)
                || !TERMINAL_TASK_STATUS.contains(binding.getStatus())) return false;
        return redisCache.deleteObjectIfValueEquals(key, value);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean b ? b : "1".equals(Objects.toString(value)) || "true".equalsIgnoreCase(Objects.toString(value));
    }
    private static int asInt(Object value, int fallback) {
        try { return Integer.parseInt(Objects.toString(value)); } catch (Exception e) { return fallback; }
    }
    private static Long parseLong(String value) {
        try { return Long.valueOf(value); } catch (Exception e) { return null; }
    }
    private static Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        return parseLong(Objects.toString(value, null));
    }
    private static long asMillis(Object value) {
        return value instanceof Date date ? date.getTime() : Long.MIN_VALUE;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ServiceException assistantFailure(String code) {
        return new ServiceException("画布占用冲突").setDetailMessage(code);
    }
    private static String safeReason(String reason) { return reason == null ? "" : reason.substring(0, Math.min(200, reason.length())); }

    private static String sessionKey(String sessionId) { return SESSION_PREFIX + sessionId; }

    private void storeSession(LiveSession session) {
        SessionMetadata metadata = new SessionMetadata(session.sessionId, session.workspaceId, session.projectId,
                session.userId, session.pageId, session.clientIp, session.connectedAt, session.lastHeartbeatAt);
        redisCache.setCacheObject(sessionKey(session.sessionId), metadata, 60, TimeUnit.SECONDS);
    }

    private List<SessionMetadata> activeSessionMetadata() {
        List<SessionMetadata> result = new ArrayList<>();
        for (String key : redisCache.scanKeys(SESSION_PREFIX + "*", 5_000)) {
            SessionMetadata value = redisCache.getCacheObject(key, SessionMetadata.class);
            if (value != null) result.add(value);
        }
        return result;
    }

    private void publishTarget(Long workspaceId, String targetSessionId, String eventJson) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("origin", instanceId);
        envelope.put("workspaceId", workspaceId);
        envelope.put("targetSessionId", targetSessionId);
        envelope.put("event", eventJson);
        redisCache.publishString(EVENT_CHANNEL, JSON.toJSONString(envelope));
    }

    @Override
    public void acceptClusterMessage(String message) {
        try {
            JSONObject envelope = JSON.parseObject(message);
            if (instanceId.equals(envelope.getString("origin"))) return;
            Long workspaceId = envelope.getLong("workspaceId");
            String target = envelope.getString("targetSessionId");
            String event = envelope.getString("event");
            if (workspaceId == null || event == null) return;
            deliverLocal(workspaceId, target, event);
            if (target != null && event.contains("\"type\":\"SESSION_CLOSED\"")) {
                closeSession(target, JSON.parseObject(event).getString("reason"));
            }
        } catch (RuntimeException ignored) { }
    }

    private record LockGrant(String objectId, String token, String redisValue, long fence) { }

    @Data
    public static class SessionMetadata implements Serializable {
        private String sessionId;
        private Long workspaceId;
        private Long projectId;
        private Long userId;
        private String pageId;
        private String clientIp;
        private long connectedAt;
        private long lastHeartbeatAt;

        public SessionMetadata() { }
        public SessionMetadata(String sessionId, Long workspaceId, Long projectId, Long userId, String pageId,
                               String clientIp, long connectedAt, long lastHeartbeatAt) {
            this.sessionId = sessionId;
            this.workspaceId = workspaceId;
            this.projectId = projectId;
            this.userId = userId;
            this.pageId = pageId;
            this.clientIp = clientIp;
            this.connectedAt = connectedAt;
            this.lastHeartbeatAt = lastHeartbeatAt;
        }
    }

    private static final class LiveSession {
        private final String sessionId;
        private final Long workspaceId;
        private final Long projectId;
        private final Long userId;
        private final String pageId;
        private final String clientIp;
        private final Consumer<String> outbound;
        private final long connectedAt = System.currentTimeMillis();
        private volatile long lastHeartbeatAt = connectedAt;
        private volatile long lastAuthorizationAt = connectedAt;
        private volatile long acknowledgedSeq;

        private LiveSession(String sessionId, Long workspaceId, Long projectId, Long userId, String pageId,
                            String clientIp, Consumer<String> outbound) {
            this.sessionId = sessionId;
            this.workspaceId = workspaceId;
            this.projectId = projectId;
            this.userId = userId;
            this.pageId = pageId;
            this.clientIp = clientIp;
            this.outbound = outbound;
        }
    }

    @Data
    public static class TicketPayload implements Serializable {
        private String sessionId;
        private Long workspaceId;
        private Long projectId;
        private Long userId;
        private String pageId;
    }
}
