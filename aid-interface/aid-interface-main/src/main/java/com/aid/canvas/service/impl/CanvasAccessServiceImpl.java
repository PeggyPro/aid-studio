package com.aid.canvas.service.impl;

import com.aid.aid.domain.AidComicProject;
import com.aid.canvas.domain.CanvasEntities.EntitlementOverride;
import com.aid.canvas.domain.CanvasEntities.ProjectMember;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CanvasAccessServiceImpl implements CanvasAccessService {
    private static final long CONFIG_CACHE_MILLIS = 5_000L;
    private final CanvasMapper canvasMapper;
    private volatile Map<String, Object> cachedConfig;
    private volatile long cachedAt;
    private final Map<String, CachedEntitlements> entitlementCache = new ConcurrentHashMap<>();

    @Override
    public AidComicProject requireProject(Long projectId, Long userId) {
        if (projectId == null || userId == null) throw new ServiceException("项目参数不完整");
        AidComicProject project = canvasMapper.selectAccessibleCanvasProject(projectId, userId);
        if (project == null) throw new ServiceException("项目不存在或无权访问");
        return project;
    }

    @Override
    public AidComicProject requireOwner(Long projectId, Long userId) {
        AidComicProject project = requireProject(projectId, userId);
        if (!Objects.equals(project.getUserId(), userId)) throw new ServiceException("仅项目所有者可以执行该操作");
        return project;
    }

    @Override
    public String requireRole(Long projectId, Long userId, String... allowedRoles) {
        AidComicProject project = requireProject(projectId, userId);
        String role;
        if (Objects.equals(project.getUserId(), userId)) {
            role = "OWNER";
        } else {
            ProjectMember member = canvasMapper.selectMember(projectId, userId);
            role = member == null || !"ACTIVE".equals(member.getStatus()) ? null : member.getRoleCode();
        }
        Set<String> allowed = Arrays.stream(allowedRoles).collect(Collectors.toSet());
        if (role == null || (!allowed.isEmpty() && !allowed.contains(role))) {
            throw new ServiceException("当前项目角色无权执行该操作");
        }
        return role;
    }

    @Override
    public Workspace requireWorkspace(Long workspaceId, Long userId, String... allowedRoles) {
        Workspace workspace = canvasMapper.selectWorkspace(workspaceId);
        if (workspace == null) throw new ServiceException("工作区不存在");
        requireRole(workspace.getProjectId(), userId, allowedRoles);
        return workspace;
    }

    @Override
    public Map<String, Object> config() {
        Map<String, Object> current = cachedConfig;
        long now = System.currentTimeMillis();
        if (current != null && now - cachedAt < CONFIG_CACHE_MILLIS) return new LinkedHashMap<>(current);
        Map<String, Object> loaded = canvasMapper.selectCanvasConfig();
        if (loaded == null) throw new ServiceException("画布系统配置未初始化");
        cachedConfig = new LinkedHashMap<>(loaded);
        cachedAt = now;
        return new LinkedHashMap<>(loaded);
    }

    @Override
    public List<Map<String, Object>> defaultEntitlements() {
        return canvasMapper.selectDefaultEntitlements();
    }

    @Override
    public Map<String, Object> effectiveEntitlements(Long userId, Long projectId) {
        Long ownerId = userId;
        String role = null;
        if (projectId != null) {
            AidComicProject project = requireProject(projectId, userId);
            ownerId = project.getUserId();
            role = Objects.equals(ownerId, userId) ? "OWNER" : requireRole(projectId, userId, "EDITOR", "VIEWER");
        }
        Map<String, EntitlementValue> values = resolveUserValues(userId);
        Map<String, EntitlementValue> ownerValues = Objects.equals(ownerId, userId) ? values : resolveUserValues(ownerId);
        Map<String, Object> systemConfig = config();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("projectId", projectId);
        result.put("projectOwnerUserId", ownerId);
        result.put("roleCode", role);
        result.put("items", toItems(values));
        result.put("projectCapacityItems", toItems(ownerValues));
        result.put("runtimeConfig", runtimeConfig(systemConfig));
        result.put("effectiveLimits", effectiveLimits(systemConfig, values, ownerValues));
        return result;
    }

    private static Map<String, Object> runtimeConfig(Map<String, Object> systemConfig) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("collaborationEnabled", asBoolean(systemConfig.get("collaborationEnabled")));
        runtime.put("maxConnectionsPerUser", asInt(systemConfig.get("maxConnectionsPerUser"), 3));
        runtime.put("operationBatchMax", Math.min(100, asInt(systemConfig.get("operationBatchMax"), 50)));
        runtime.put("operationPayloadMaxBytes", Math.min(1024 * 1024,
                asInt(systemConfig.get("operationPayloadMaxBytes"), 262144)));
        runtime.put("lockLeaseSeconds", asInt(systemConfig.get("lockLeaseSeconds"), 30));
        runtime.put("lockRenewSeconds", asInt(systemConfig.get("lockRenewSeconds"), 10));
        runtime.put("textMergeMillis", asInt(systemConfig.get("textMergeMillis"), 300));
        runtime.put("heartbeatSeconds", 15);
        return runtime;
    }

    private static Map<String, Object> effectiveLimits(Map<String, Object> systemConfig,
                                                        Map<String, EntitlementValue> actorValues,
                                                        Map<String, EntitlementValue> ownerValues) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxProjectsPerOwner", capacityLimit(systemConfig, actorValues,
                "maxProjectsPerOwner", "canvas.project.max", 20));
        limits.put("maxNodesPerWorkspace", capacityLimit(systemConfig, ownerValues,
                "maxNodesPerWorkspace", "canvas.node.max", 1000));
        limits.put("maxMembersPerProject", capacityLimit(systemConfig, ownerValues,
                "maxMembersPerProject", "canvas.member.max", 10));
        limits.put("maxOnlineEditors", capacityLimit(systemConfig, ownerValues,
                "maxOnlineEditors", "canvas.online.max", 10));
        return limits;
    }

    private static int capacityLimit(Map<String, Object> systemConfig, Map<String, EntitlementValue> values,
                                     String configKey, String entitlementCode, int fallback) {
        int configured = asInt(systemConfig.get(configKey), fallback);
        EntitlementValue entitlement = values.get(entitlementCode);
        if (entitlement == null) return configured;
        if (!entitlement.enabled) return 0;
        try {
            return Math.min(configured, new BigDecimal(entitlement.value).intValueExact());
        } catch (RuntimeException ignored) {
            return configured;
        }
    }

    private Map<String, EntitlementValue> resolveUserValues(Long userId) {
        String cacheKey = String.valueOf(userId);
        CachedEntitlements cached = entitlementCache.get(cacheKey);
        long nowMillis = System.currentTimeMillis();
        if (cached != null && nowMillis - cached.createdAt < 2_000L) return new LinkedHashMap<>(cached.values);
        Map<String, EntitlementValue> values = new LinkedHashMap<>();
        for (Map<String, Object> item : canvasMapper.selectDefaultEntitlements()) {
            String code = Objects.toString(item.get("entitlementCode"), "");
            values.put(code, new EntitlementValue(code, Objects.toString(item.get("name"), code),
                    Objects.toString(item.get("valueType"), "STRING"), Objects.toString(item.get("value"), ""),
                    asBoolean(item.get("enabled")), "DEFAULT"));
        }
        applyOverrides(values, canvasMapper.selectActiveOverrides(userId, new Date()));
        if (entitlementCache.size() > 1_000) entitlementCache.clear();
        entitlementCache.put(cacheKey, new CachedEntitlements(new LinkedHashMap<>(values), nowMillis));
        return values;
    }

    @Override
    public void requireFeature(Long userId, Long projectId, String entitlementCode) {
        EntitlementValue value = resolve(userId, projectId, entitlementCode);
        if (value == null || !value.enabled || !asBoolean(value.value)) {
            throw new ServiceException("当前账号未获得该画布功能权益");
        }
    }

    @Override
    public int entitlementLimit(Long userId, Long projectId, String entitlementCode, int fallback) {
        EntitlementValue value = resolve(userId, projectId, entitlementCode);
        if (value == null) return fallback;
        if (!value.enabled) return 0;
        try { return new BigDecimal(value.value).intValueExact(); }
        catch (Exception ignored) { return fallback; }
    }

    @Override
    public void evictConfiguration() {
        cachedConfig = null;
        cachedAt = 0L;
        entitlementCache.clear();
    }

    private EntitlementValue resolve(Long userId, Long projectId, String code) {
        Map<String, Object> effective = effectiveEntitlements(userId, projectId);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) effective.get("items");
        for (Map<String, Object> item : items) {
            if (code.equals(item.get("entitlementCode"))) {
                return new EntitlementValue(code, Objects.toString(item.get("name"), code),
                        Objects.toString(item.get("valueType"), "STRING"), Objects.toString(item.get("value"), ""),
                        asBoolean(item.get("enabled")), Objects.toString(item.get("source"), "DEFAULT"));
            }
        }
        return null;
    }

    private void applyOverrides(Map<String, EntitlementValue> values, List<EntitlementOverride> overrides) {
        for (EntitlementOverride override : overrides) {
            EntitlementValue current = values.get(override.getEntitlementCode());
            if (current == null) continue;
            if ("DENY".equals(override.getMode())) {
                values.put(current.code, new EntitlementValue(current.code, current.name,
                        current.valueType, current.value, false, "USER_DENY"));
                continue;
            }
            if (!current.enabled && "USER_DENY".equals(current.source)) continue;
            switch (override.getMode()) {
                case "OVERRIDE" -> values.put(current.code, new EntitlementValue(current.code, current.name,
                        current.valueType, override.getEntitlementValue(), true, "USER_OVERRIDE"));
                case "ADD" -> {
                    if ("NUMBER".equals(current.valueType) || "INTEGER".equals(current.valueType)) {
                        BigDecimal sum = new BigDecimal(current.value).add(new BigDecimal(override.getEntitlementValue()));
                        values.put(current.code, new EntitlementValue(current.code, current.name, current.valueType,
                                sum.stripTrailingZeros().toPlainString(), true, "USER_ADD"));
                    }
                }
                default -> { }
            }
        }
    }

    private static List<Map<String, Object>> toItems(Map<String, EntitlementValue> values) {
        List<Map<String, Object>> items = new ArrayList<>();
        values.values().forEach(v -> items.add(v.toMap()));
        return items;
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        String text = Objects.toString(value, "");
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static int asInt(Object value, int fallback) {
        try { return Integer.parseInt(Objects.toString(value)); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private record EntitlementValue(String code, String name, String valueType, String value,
                                    boolean enabled, String source) {
        Map<String, Object> toMap() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entitlementCode", code);
            item.put("name", name);
            item.put("valueType", valueType);
            item.put("value", value);
            item.put("enabled", enabled);
            item.put("source", source);
            return item;
        }
    }

    private record CachedEntitlements(Map<String, EntitlementValue> values, long createdAt) { }
}
