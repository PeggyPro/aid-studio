package com.aid.canvas.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 自由画布持久化实体集合。 */
public final class CanvasEntities {
    private CanvasEntities() { }

    @Data
    @TableName("aid_canvas_workspace")
    public static class Workspace implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private String workspaceType;
        private Integer schemaVersion;
        private Long revision;
        private Long eventSeq;
        private Integer nodeCount;
        private Integer edgeCount;
        private Integer groupCount;
        private Long storageBytes;
        private String delFlag;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_canvas_object")
    public static class CanvasObject implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long workspaceId;
        private String objectId;
        private String objectType;
        private Long objectVersion;
        /** 最近一次成功提交使用的租约 fencing token，防止过期租约晚到覆盖新持有者。 */
        private Long lockFence;
        private String contentJson;
        private String contentHash;
        private Integer deleted;
        private Long lastActorUserId;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_canvas_operation")
    public static class Operation implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long workspaceId;
        private String operationId;
        private Long actorUserId;
        private Long workspaceRevision;
        private Long eventSeq;
        private String operationType;
        private String payloadJson;
        private String payloadHash;
        private Date createTime;
    }

    /** 每次对象操作的前后快照，用于严格版本校验后的个人撤销。 */
    @Data
    @TableName("aid_canvas_operation_object")
    public static class OperationObject implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long operationRowId;
        private Long projectId;
        private Long workspaceId;
        private String operationId;
        private String objectId;
        private String objectType;
        private Long beforeVersion;
        private Long afterVersion;
        private Integer beforeDeleted;
        private Integer afterDeleted;
        private String beforeContentJson;
        private Date createTime;
    }

    /** 通用项目设置。settingKey 定义功能语义，settingValue 保存规范化 JSON。 */
    @Data
    @TableName("aid_project_setting")
    public static class ProjectSetting implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private String settingKey;
        private String settingValue;
        private Long settingVersion;
        private Long lastActorUserId;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_project_member")
    public static class ProjectMember implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long userId;
        private String roleCode;
        private String status;
        private Long invitedBy;
        private Date joinedTime;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_project_invite")
    public static class ProjectInvite implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private String tokenHash;
        private String defaultRole;
        private Integer maxUses;
        private Integer usedCount;
        private Date expireTime;
        private String status;
        private Long creatorUserId;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_project_join_application")
    public static class JoinApplication implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long inviteId;
        private Long applicantUserId;
        private String requestedRole;
        private String status;
        private String applyMessage;
        private Long reviewerUserId;
        private String reviewRemark;
        private Date reviewTime;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_project_share")
    public static class ProjectShare implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long workspaceId;
        private String tokenHash;
        private Long snapshotRevision;
        private String snapshotJson;
        private Date expireTime;
        private String status;
        private Long creatorUserId;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_canvas_task_binding")
    public static class TaskBinding implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long workspaceId;
        private String objectId;
        private Long inputObjectVersion;
        private Long taskId;
        private Long actorUserId;
        private String mediaType;
        private String resultFormat;
        private String operationId;
        private String requestHash;
        private String status;
        /** 统一媒体服务的稳定调用标识，提交结果未知时用于恢复。 */
        private String callId;
        private String resultUrl;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_canvas_resource")
    public static class CanvasResource implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long workspaceId;
        private Long ownerUserId;
        private String sourceType;
        private Long sourceId;
        private String mediaType;
        private String storageUrl;
        private Long fileSize;
        private String mimeType;
        private String metadataJson;
        private String status;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_canvas_user_view")
    public static class UserView implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long workspaceId;
        private Long userId;
        private Long viewVersion;
        private String viewJson;
        private Date createTime;
        private Date updateTime;
    }

    /** 个人节点草稿不进入共享对象版本，断线或权限暂时失效后仍可恢复。 */
    @Data
    @TableName("aid_canvas_node_draft")
    public static class NodeDraft implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long workspaceId;
        private String objectId;
        private Long userId;
        private Long draftVersion;
        private Long baseObjectVersion;
        private String draftJson;
        private String draftHash;
        private Date createTime;
        private Date updateTime;
    }

    /** 节点当前结果选择与节点输入版本分离，迟到结果只能进入历史。 */
    @Data
    @TableName("aid_canvas_node_result_selection")
    public static class NodeResultSelection implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long projectId;
        private Long workspaceId;
        private String objectId;
        private Long taskId;
        private Long resultId;
        private Long inputObjectVersion;
        private Long selectionVersion;
        private Long lastActorUserId;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @TableName("aid_entitlement_user_override")
    public static class EntitlementOverride implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        /** 通用权益域；本期为 canvas，后续可扩展 director 等公共场景。 */
        private String scopeCode;
        /** USER 或 TEAM；本期管理接口写 USER。 */
        private String subjectType;
        private Long subjectId;
        /** 兼容画布后台外部 userId 契约，不对应数据库列。 */
        @TableField(exist = false)
        private Long userId;
        private String entitlementCode;
        private String mode;
        private String entitlementValue;
        private Date startTime;
        private Date endTime;
        private String status;
        private String reason;
        private String createBy;
        private Date createTime;
        private String revokeBy;
        private Date revokeTime;
        private String revokeReason;
    }

    @Data
    @TableName("aid_canvas_audit_log")
    public static class AuditLog implements Serializable {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String operator;
        private String operatorName;
        private String targetType;
        private String targetId;
        private Long targetUserId;
        private String action;
        private String detailJson;
        private Date createTime;
    }
}
