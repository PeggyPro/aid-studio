package com.aid.canvas.service.impl;

import com.aid.canvas.domain.CanvasEntities.EntitlementOverride;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasAdminService;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.core.service.ISysUserService;
import com.alibaba.fastjson2.JSON;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CanvasAdminServiceImpl implements CanvasAdminService {
    private final CanvasMapper canvasMapper;
    private final CanvasAccessService accessService;
    private final CanvasCollaborationService collaborationService;
    private final ISysUserService userService;

    @Override
    public Map<String, Object> projects(String projectName, Long ownerUserId, Integer status, int pageNum, int pageSize) {
        PageHelper.startPage(safePage(pageNum), safeSize(pageSize));
        List<Map<String, Object>> items = canvasMapper.selectAdminProjects(trim(projectName), ownerUserId, status);
        long total = new PageInfo<>(items).getTotal();
        Map<Long, Set<Long>> onlineUsers = new LinkedHashMap<>();
        for (Map<String, Object> session : collaborationService.listSessions(null, null)) {
            Long workspaceId = asLong(session.get("workspaceId"));
            Long sessionUserId = asLong(session.get("userId"));
            if (workspaceId != null && sessionUserId != null) {
                onlineUsers.computeIfAbsent(workspaceId, ignored -> new HashSet<>()).add(sessionUserId);
            }
        }
        items.forEach(item -> item.put("onlineCount", onlineUsers.getOrDefault(
                asLong(item.get("workspaceId")), Set.of()).size()));
        return page(items.stream().map(item -> JsonSafeId.fields(item,
                "projectId", "ownerUserId", "workspaceId")).toList(), total);
    }

    @Override
    public Map<String, Object> projectDetail(Long projectId) {
        Map<String, Object> detail = canvasMapper.selectAdminProjectDetail(projectId);
        if (detail == null) throw new ServiceException("自由画布项目不存在");
        detail.put("memberCount", canvasMapper.countActiveMembers(projectId));
        detail.put("onlineCount", collaborationService.onlineCount(asLong(detail.get("workspaceId"))));
        return JsonSafeId.fields(detail, "projectId", "ownerUserId", "workspaceId");
    }

    @Override public Map<String, Object> config() { return accessService.config(); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(CanvasRequests.AdminConfig request, String operator) {
        Map<String, Object> current = accessService.config();
        Map<String, Object> next = new LinkedHashMap<>(current);
        put(next, "collaborationEnabled", request.getCollaborationEnabled());
        put(next, "maxProjectsPerOwner", request.getMaxProjectsPerOwner());
        put(next, "maxNodesPerWorkspace", request.getMaxNodesPerWorkspace());
        put(next, "maxMembersPerProject", request.getMaxMembersPerProject());
        put(next, "maxOnlineEditors", request.getMaxOnlineEditors());
        put(next, "maxConnectionsPerUser", request.getMaxConnectionsPerUser());
        put(next, "operationBatchMax", request.getOperationBatchMax());
        put(next, "operationPayloadMaxBytes", request.getOperationPayloadMaxBytes());
        put(next, "lockLeaseSeconds", request.getLockLeaseSeconds());
        put(next, "lockRenewSeconds", request.getLockRenewSeconds());
        put(next, "textMergeMillis", request.getTextMergeMillis());
        put(next, "inviteDefaultDays", request.getInviteDefaultDays());
        put(next, "inviteDefaultUses", request.getInviteDefaultUses());
        validateConfig(next);
        if (canvasMapper.updateCanvasConfig(next) != 1) throw new ServiceException("画布系统配置更新失败");
        audit(operator, "CONFIG", "1", null, "UPDATE_CONFIG", JSON.toJSONString(next));
        afterCommit(() -> {
            accessService.evictConfiguration();
            if (!asBoolean(next.get("collaborationEnabled"))) {
                collaborationService.listSessions(null, null).forEach(s -> collaborationService.terminateSession(
                        Objects.toString(s.get("sessionId")), "实时协作已由管理员关闭"));
            }
        });
    }

    @Override public Map<String, Object> defaultEntitlements() { return Map.of("items", accessService.defaultEntitlements()); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDefaultEntitlements(CanvasRequests.DefaultEntitlements request, String operator) {
        Set<String> known = new HashSet<>();
        accessService.defaultEntitlements().forEach(item -> known.add(Objects.toString(item.get("entitlementCode"))));
        Set<String> submitted = new HashSet<>();
        for (CanvasRequests.EntitlementItem item : request.getItems()) {
            if (!known.contains(item.getEntitlementCode())) throw new ServiceException("未知权益编码: " + item.getEntitlementCode());
            if (!submitted.add(item.getEntitlementCode())) throw new ServiceException("权益编码重复: " + item.getEntitlementCode());
            String type = item.getValueType().toUpperCase();
            if (!Set.of("BOOLEAN", "INTEGER", "STRING").contains(type)) throw new ServiceException("不支持的权益值类型");
            validateEntitlementValue(type, item.getValue());
            canvasMapper.upsertDefaultEntitlement(item.getEntitlementCode(), type, item.getValue(),
                    item.getEnabled() == null || item.getEnabled());
        }
        audit(operator, "ENTITLEMENT_DEFAULT", "default", null, "UPDATE_DEFAULT_ENTITLEMENT",
                JSON.toJSONString(request.getItems()));
        afterCommit(accessService::evictConfiguration);
    }

    @Override
    public Map<String, Object> overrides(Long userId, String entitlementCode, String status, int pageNum, int pageSize) {
        String normalizedStatus = trim(status) == null ? null : status.trim().toUpperCase();
        if (normalizedStatus != null && !Set.of("PENDING", "ACTIVE", "EXPIRED", "REVOKED").contains(normalizedStatus)) {
            throw new ServiceException("status 仅支持 PENDING、ACTIVE、EXPIRED、REVOKED");
        }
        PageHelper.startPage(safePage(pageNum), safeSize(pageSize));
        List<Map<String, Object>> items = canvasMapper.selectAdminOverrides(userId, trim(entitlementCode), normalizedStatus);
        long total = new PageInfo<>(items).getTotal();
        return page(items.stream().map(item -> JsonSafeId.fields(item,
                "id", "subjectId", "userId")).toList(), total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOverride(CanvasRequests.EntitlementOverride request, String operator) {
        SysUser targetUser = userService.selectUserById(request.getUserId());
        if (targetUser == null || !"0".equals(targetUser.getDelFlag())) {
            throw new ServiceException("目标用户不存在或已删除");
        }
        String mode = request.getMode().toUpperCase();
        if (!Set.of("ADD", "OVERRIDE", "DENY").contains(mode)) throw new ServiceException("mode 仅支持 ADD、OVERRIDE、DENY");
        Map<String, Object> definition = accessService.defaultEntitlements().stream()
                .filter(item -> request.getEntitlementCode().equals(item.get("entitlementCode"))).findFirst()
                .orElseThrow(() -> new ServiceException("未知权益编码"));
        if (request.getEndTime() != null && request.getStartTime() != null && !request.getEndTime().after(request.getStartTime()))
            throw new ServiceException("结束时间必须晚于开始时间");
        String value = request.getValue();
        if (!"DENY".equals(mode)) {
            if (value == null || value.isBlank()) throw new ServiceException("ADD/OVERRIDE 必须提供 value");
            validateEntitlementValue(Objects.toString(definition.get("valueType")), value);
        } else value = null;
        String valueType = Objects.toString(definition.get("valueType"));
        if ("ADD".equals(mode) && !Set.of("INTEGER", "NUMBER").contains(valueType)) {
            throw new ServiceException("ADD 仅支持数量型权益");
        }
        EntitlementOverride override = new EntitlementOverride();
        override.setScopeCode("canvas");
        override.setSubjectType("USER");
        override.setSubjectId(request.getUserId());
        override.setUserId(request.getUserId());
        override.setEntitlementCode(request.getEntitlementCode());
        override.setMode(mode);
        override.setEntitlementValue(value);
        override.setStartTime(request.getStartTime());
        override.setEndTime(request.getEndTime());
        override.setStatus("ACTIVE");
        override.setReason(request.getReason().trim());
        override.setCreateBy(operator);
        canvasMapper.insertEntitlementOverride(override);
        audit(operator, "ENTITLEMENT_OVERRIDE", String.valueOf(override.getId()), request.getUserId(), "CREATE_OVERRIDE",
                JSON.toJSONString(Map.of("entitlementCode", request.getEntitlementCode(), "mode", mode)));
        afterCommit(() -> {
            accessService.evictConfiguration();
            closeUserSessions(request.getUserId(), "账号画布权益已调整，请重新进入画布");
        });
        return override.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeOverride(CanvasRequests.RevokeOverride request, String operator) {
        EntitlementOverride existing = canvasMapper.selectEntitlementOverride(request.getId());
        if (existing == null || !"ACTIVE".equals(existing.getStatus())) throw new ServiceException("权益授权不存在或已撤销");
        if (canvasMapper.revokeEntitlementOverride(request.getId(), operator, request.getReason().trim()) != 1)
            throw new ServiceException("权益授权状态已变化");
        audit(operator, "ENTITLEMENT_OVERRIDE", String.valueOf(request.getId()), existing.getUserId(), "REVOKE_OVERRIDE",
                JSON.toJSONString(Map.of("reason", request.getReason())));
        afterCommit(() -> {
            accessService.evictConfiguration();
            closeUserSessions(existing.getUserId(), "账号画布权益已调整，请重新进入画布");
        });
    }

    @Override
    public Map<String, Object> sessions(Long projectId, Long userId, int pageNum, int pageSize) {
        List<Map<String, Object>> items = enrichSessions(collaborationService.listSessions(projectId, userId));
        return slice(items, pageNum, pageSize);
    }

    @Override
    public Map<String, Object> locks(Long projectId, Long workspaceId, Long userId, int pageNum, int pageSize) {
        List<Map<String, Object>> items = enrichSessions(collaborationService.listLocks(projectId, workspaceId, userId));
        return slice(items, pageNum, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void terminateSession(CanvasRequests.TerminateSession request, String operator) {
        List<Map<String, Object>> matches = collaborationService.listSessions(null, null).stream()
                .filter(s -> request.getSessionId().equals(s.get("sessionId"))).toList();
        if (matches.isEmpty()) throw new ServiceException("在线会话不存在或已结束");
        if (!collaborationService.terminateSession(request.getSessionId(), request.getReason())) throw new ServiceException("在线会话状态已变化");
        audit(operator, "SESSION", request.getSessionId(), asLong(matches.get(0).get("userId")), "TERMINATE_SESSION",
                JSON.toJSONString(Map.of("reason", request.getReason())));
    }

    @Override
    public Map<String, Object> audits(String operator, Long targetUserId, String action, int pageNum, int pageSize) {
        PageHelper.startPage(safePage(pageNum), safeSize(pageSize));
        List<Map<String, Object>> items = canvasMapper.selectAudits(trim(operator), targetUserId, trim(action));
        long total = new PageInfo<>(items).getTotal();
        return page(items.stream().map(item -> JsonSafeId.fields(item,
                "id", "targetUserId")).toList(), total);
    }

    private List<Map<String, Object>> enrichSessions(List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<Long, Map<String, Object>> projectCache = new LinkedHashMap<>();
        for (Map<String, Object> original : source) {
            Map<String, Object> item = new LinkedHashMap<>(original);
            Long projectId = asLong(item.get("projectId"));
            Map<String, Object> project = projectId == null ? null : projectCache.computeIfAbsent(projectId, canvasMapper::selectAdminProjectDetail);
            item.put("projectName", project == null ? null : project.get("projectName"));
            item.putIfAbsent("userName", null);
            result.add(JsonSafeId.fields(item, "projectId", "workspaceId", "userId", "taskId"));
        }
        return result;
    }

    private void closeUserSessions(Long userId, String reason) {
        collaborationService.listSessions(null, userId).forEach(s -> collaborationService.terminateSession(
                Objects.toString(s.get("sessionId")), reason));
    }

    private void audit(String operator, String targetType, String targetId, Long targetUserId, String action, String detail) {
        canvasMapper.insertAudit(operator, operator, targetType, targetId, targetUserId, action, detail);
    }

    private static void validateConfig(Map<String, Object> value) {
        int members = asInt(value.get("maxMembersPerProject"));
        int online = asInt(value.get("maxOnlineEditors"));
        int lease = asInt(value.get("lockLeaseSeconds"));
        int renew = asInt(value.get("lockRenewSeconds"));
        if (members < 1 || online < 1 || online > members) throw new ServiceException("在线人数必须大于0且不能超过成员上限");
        if (lease < 10 || renew < 5 || renew >= lease) throw new ServiceException("续期间隔必须小于租约时长");
        if (asInt(value.get("operationBatchMax")) < 1 || asInt(value.get("operationBatchMax")) > 100)
            throw new ServiceException("单批操作数必须在1到100之间");
        if (asInt(value.get("operationPayloadMaxBytes")) < 1024 || asInt(value.get("operationPayloadMaxBytes")) > 1024 * 1024)
            throw new ServiceException("单批消息大小必须在1KB到1MB之间");
    }

    private static void validateEntitlementValue(String type, String value) {
        if ("BOOLEAN".equals(type) && !("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)))
            throw new ServiceException("布尔权益值必须为 true 或 false");
        if (("INTEGER".equals(type) || "NUMBER".equals(type))) {
            try { if (new BigDecimal(value).compareTo(BigDecimal.ZERO) < 0) throw new Exception(); }
            catch (Exception e) { throw new ServiceException("数量权益值必须为非负整数"); }
            if (value.contains(".")) throw new ServiceException("数量权益值必须为非负整数");
        }
    }

    private static Map<String, Object> slice(List<Map<String, Object>> source, int pageNum, int pageSize) {
        int page = safePage(pageNum), size = safeSize(pageSize), from = Math.min(source.size(), (page - 1) * size);
        int to = Math.min(source.size(), from + size);
        return page(source.subList(from, to), source.size());
    }
    private static Map<String, Object> page(List<?> items, long total) { return Map.of("items", items, "total", total); }
    private static int safePage(int page) { return Math.max(1, page); }
    private static int safeSize(int size) { return Math.max(1, Math.min(100, size)); }
    private static void put(Map<String, Object> map, String key, Object value) { if (value != null) map.put(key, value); }
    private static String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static int asInt(Object value) { try { return Integer.parseInt(Objects.toString(value)); } catch (Exception e) { return -1; } }
    private static boolean asBoolean(Object value) { return value instanceof Boolean b ? b : "1".equals(Objects.toString(value)) || "true".equalsIgnoreCase(Objects.toString(value)); }
    private static Long asLong(Object value) { return value instanceof Number n ? n.longValue() : value == null ? null : Long.valueOf(value.toString()); }
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }
}
