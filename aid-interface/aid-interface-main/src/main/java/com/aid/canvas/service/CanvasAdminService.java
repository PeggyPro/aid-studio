package com.aid.canvas.service;

import com.aid.canvas.dto.CanvasRequests;

import java.util.Map;

/** 后台画布治理与配置接口。 */
public interface CanvasAdminService {
    Map<String, Object> projects(String projectName, Long ownerUserId, Integer status, int pageNum, int pageSize);
    Map<String, Object> projectDetail(Long projectId);
    Map<String, Object> config();
    void updateConfig(CanvasRequests.AdminConfig request, String operator);
    Map<String, Object> defaultEntitlements();
    void updateDefaultEntitlements(CanvasRequests.DefaultEntitlements request, String operator);
    Map<String, Object> overrides(Long userId, String entitlementCode, String status, int pageNum, int pageSize);
    Long createOverride(CanvasRequests.EntitlementOverride request, String operator);
    void revokeOverride(CanvasRequests.RevokeOverride request, String operator);
    Map<String, Object> sessions(Long projectId, Long userId, int pageNum, int pageSize);
    Map<String, Object> locks(Long projectId, Long workspaceId, Long userId, int pageNum, int pageSize);
    void terminateSession(CanvasRequests.TerminateSession request, String operator);
    Map<String, Object> audits(String operator, Long targetUserId, String action, int pageNum, int pageSize);
}
