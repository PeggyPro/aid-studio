package com.aid.canvas.service;

import com.aid.aid.domain.AidComicProject;
import com.aid.canvas.domain.CanvasEntities.Workspace;

import java.util.List;
import java.util.Map;

/** 项目角色与权益的唯一判定入口。 */
public interface CanvasAccessService {
    AidComicProject requireProject(Long projectId, Long userId);
    AidComicProject requireOwner(Long projectId, Long userId);
    String requireRole(Long projectId, Long userId, String... allowedRoles);
    Workspace requireWorkspace(Long workspaceId, Long userId, String... allowedRoles);
    Map<String, Object> config();
    Map<String, Object> effectiveEntitlements(Long userId, Long projectId);
    void requireFeature(Long userId, Long projectId, String entitlementCode);
    int entitlementLimit(Long userId, Long projectId, String entitlementCode, int fallback);
    List<Map<String, Object>> defaultEntitlements();
    void evictConfiguration();
}
