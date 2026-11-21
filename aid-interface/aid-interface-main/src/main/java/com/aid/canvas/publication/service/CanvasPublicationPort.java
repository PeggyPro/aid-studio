package com.aid.canvas.publication.service;

import com.alibaba.fastjson2.JSONObject;
import java.util.Map;

/** 运行模块提供的权限、冻结和独立复制边界；发布审核不依赖具体画布实现。 */
public interface CanvasPublicationPort {
    void requireOwner(Long projectId, Long userId);
    void requireReader(Long projectId, Long userId);
    default void requireEditor(Long projectId, Long userId) { requireOwner(projectId,userId); }
    default void requireCopyCapacity(Long userId,String snapshotJson) {
        throw new com.aid.common.exception.ServiceException("当前环境尚未启用复制容量检查");
    }
    boolean projectExists(Long projectId);
    long revision(Long projectId);
    /** 在同一事务内锁定工作区，校验版本、资源归属和完整性；不得包含会话或凭证。 */
    Capture capture(Long projectId, Long userId, long expectedRevision, Long videoResourceId,
                    Long coverResourceId, String sceneDocumentJson);
    /** 新建私有项目，完整保留内容，仅重映射身份和资源引用。 */
    Long copy(String snapshotJson, Long userId, String projectName, String operationId);
    /** 对公开输出执行字段白名单投影；不能返回内部来源身份或执行数据。 */
    JSONObject preview(String snapshotJson);
    default JSONObject compare(String referenceSnapshot,String currentSnapshot,Long currentProjectId) {
        return JSONObject.of("available",false);
    }
    default JSONObject authorizeResource(Long resourceId, Long userId) {
        throw new com.aid.common.exception.ServiceException("复制资源读取暂不可用");
    }
    /** 保存前校验场景格式、引用及资源权限；不允许裸地址或私有会话数据。 */
    Map<String, String> workspaceSceneReferences(Long projectId);
    void validateSceneDocument(Long projectId, Long userId, JSONObject document, Map<String, String> legacyGaps);
    void lockSceneEditor(Long projectId, Long userId);
    default void requireBundleEditor(Long workspaceId,Long userId){throw new com.aid.common.exception.ServiceException("模型资源登记未启用");}
    default JSONObject existingBundle(Long workspaceId,Long userId,String operationId,String hash){return null;}
    default JSONObject registerBundle(Long workspaceId,Long userId,String operationId,String hash,JSONObject manifest){throw new com.aid.common.exception.ServiceException("模型资源登记未启用");}
    /** Only describe already registered roots; reading never creates receipts or resources. */
    default com.alibaba.fastjson2.JSONArray readBundles(Long workspaceId,Long userId,java.util.List<Long> resourceIds){throw new com.aid.common.exception.ServiceException("模型资源读取未启用");}
    record Capture(long revision, String projectName, String snapshotJson, String videoUrl, String coverUrl) {}
}
