package com.aid.canvas.dto;

import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.alibaba.fastjson2.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;
import java.util.List;

/** 自由画布接口请求模型。业务写入只通过 HTTP 进入。 */
public final class CanvasRequests {
    private CanvasRequests() { }

    @Data public static class ProjectId { @NotNull private Long projectId; }

    @Data public static class CreateProject {
        @NotBlank @Size(max = 100) private String projectName;
        @Size(max = 500) private String projectDesc;
        @Size(max = 500) private String coverUrl;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String operationId;
    }

    @Data public static class ProjectList {
        @Size(max = 100) private String projectName;
        private String sortBy = "UPDATED_AT";
        private String sortDirection = "DESC";
        @Min(1) private Integer pageNum = 1;
        @Min(1) @Max(100) private Integer pageSize = 20;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ImportProject extends CreateProject {
        @NotEmpty @Size(max = 2000) private List<@Valid ImportObject> objects;
    }

    @Data public static class ImportObject {
        @NotBlank @Size(max = 64) private String localObjectId;
        @NotBlank private String objectType;
        @NotNull private Object payload;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class UpdateProject extends ProjectId {
        @Size(max = 100) private String projectName;
        @Size(max = 500) private String projectDesc;
        @Size(max = 500) private String coverUrl;
        /** 旧客户端兼容识别；公开权限只允许发布审核链路变更。 */
        private Boolean publicCanvas;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class LoadWorkspace extends ProjectId {
        @Min(0) private Long sinceSeq;
    }

    @Data public static class WorkspaceId { @NotNull private Long workspaceId; }

    @Data @EqualsAndHashCode(callSuper = true) public static class Changes extends WorkspaceId {
        @NotNull @Min(0) private Long afterSeq;
        @Min(1) @Max(500) private Integer limit = 200;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class SaveView extends WorkspaceId {
        @NotNull @Min(0) private Long expectedVersion;
        @NotNull private Object view;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class SaveDraft extends WorkspaceId {
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String objectId;
        @NotNull @Min(0) private Long expectedDraftVersion;
        @NotNull @Min(0) private Long baseObjectVersion;
        @NotNull private Object payload;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class DeleteDraft extends WorkspaceId {
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String objectId;
        @NotNull @Min(1) private Long expectedDraftVersion;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ProjectSettingUpdate extends ProjectId {
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*){2,9}")
        private String settingKey;
        @NotNull @Min(0) private Long expectedVersion;
        @NotNull private Object value;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class Mutate extends WorkspaceId {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String operationId;
        @NotBlank @Size(max = 64) private String sessionId;
        @NotNull @Size(max = 100) private List<@NotNull @Valid Mutation> mutations;
        @Valid private SceneWrite sceneWrite;
    }

    @Data public static class SceneWrite {
        @NotNull @Min(0) private Long expectedVersion;
        @NotNull private JSONObject document;
        @Valid private SceneAdoption adoption;
    }

    @Data public static class SceneAdoption {
        @NotBlank @Size(max = 64) private String sceneId;
        @NotBlank @Size(max = 64) private String projectId;
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") private String stepId;
    }

    /** 批量复制由服务端生成新对象 ID，保证整批复制与引用重映射原子完成。 */
    @Data @EqualsAndHashCode(callSuper = true) public static class CopyObjects extends WorkspaceId {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String operationId;
        @NotEmpty @Size(max = 100) private List<@Valid CopySource> sources;
    }

    @Data public static class CopySource {
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String sourceObjectId;
        @NotNull @Min(1) private Long expectedVersion;
    }

    /** 个人撤销只允许撤销本人操作，并要求原操作涉及的所有对象均未被后续修改。 */
    @Data @EqualsAndHashCode(callSuper = true) public static class UndoOperation extends WorkspaceId {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String operationId;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String targetOperationId;
        @NotBlank @Size(max = 64) private String sessionId;
        @NotNull @Size(max = 100) private List<@NotNull @Valid LockItem> locks;
    }

    @Data public static class Mutation {
        @NotBlank @Size(max = 64) private String objectId;
        @NotBlank private String objectType;
        @NotBlank private String action;
        @NotNull @Min(0) private Long expectedVersion;
        @NotBlank @Size(max = 128) private String lockToken;
        private Object payload;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class MemberRole extends ProjectId {
        @NotNull private Long userId;
        @NotBlank private String roleCode;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class MemberUser extends ProjectId { @NotNull private Long userId; }

    @Data @EqualsAndHashCode(callSuper = true) public static class CreateInvite extends ProjectId {
        @NotBlank private String roleCode;
        @Min(1) @Max(365) private Integer validDays;
        @Min(1) @Max(1000) private Integer maxUses;
    }

    @Data public static class Id { @NotNull private Long id; }

    @Data @EqualsAndHashCode(callSuper = true) public static class ProjectEntityId extends ProjectId { @NotNull private Long id; }

    @Data public static class ApplyInvite {
        @NotBlank @Size(max = 128) private String inviteToken;
        @Size(max = 500) private String message;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ReviewApplication extends Id {
        @NotBlank private String decision;
        @Size(max = 500) private String remark;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class CreateShare extends ProjectId {
        @Min(1) @Max(365) private Integer validDays;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ResourceRegister extends WorkspaceId {
        @NotBlank private String sourceType;
        @NotNull @Min(1) private Long sourceId;
        @Schema(description = "资产中心分类编码，来源为 ASSET_CENTER 时必填")
        @Size(max = 32) private String sourceCategoryCode;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ResourceMetadataPrepare extends WorkspaceId {
        @NotNull @Min(1) private Long resourceId;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ResourceTransfer extends WorkspaceId {
        @NotNull private Long resourceId;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String operationId;
        @Size(max = 40) private String targetCategory;
        @NotBlank @Size(max = 100) private String assetName;
        @Size(max = 500) private String remark;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ImageSplit extends WorkspaceId {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String sourceObjectId;
        @NotNull @Min(1) private Long sourceResourceId;
        @NotNull @Min(1) private Long expectedVersion;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String clientRequestId;
        @NotBlank @Pattern(regexp = "GRID|REGIONS") private String mode;
        @Min(1) @Max(5) private Integer rows;
        @Min(1) @Max(5) private Integer columns;
        @Size(max = 25) private List<@Valid GridCell> selectedCells;
        @Size(max = 25) private List<@Valid PixelRegion> regions;
    }

    @Data public static class GridCell {
        @NotNull @Min(0) @Max(4) private Integer row;
        @NotNull @Min(0) @Max(4) private Integer col;
    }

    @Data public static class PixelRegion {
        @NotNull @Min(0) private Integer x;
        @NotNull @Min(0) private Integer y;
        @NotNull @Min(1) private Integer width;
        @NotNull @Min(1) private Integer height;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ImageLayerMerge extends WorkspaceId {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String sourceObjectId;
        @NotNull @Min(1) private Long expectedVersion;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String clientRequestId;
        @NotNull @Valid private ImageCanvasSize canvas;
        @Min(1) private Long backgroundResourceId;
        @NotEmpty @Size(max = 25) private List<@NotNull @Valid ImageLayer> layers;
    }

    @Data public static class ImageCanvasSize {
        @NotNull @Min(1) @Max(32000) private Integer width;
        @NotNull @Min(1) @Max(32000) private Integer height;
    }

    @Data public static class ImageLayer {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String layerId;
        @NotNull @Min(1) private Long resourceId;
        @NotNull @Valid private NormalizedRect rect;
        @NotNull @DecimalMin("-360") @DecimalMax("360") private Double rotationDeg;
        @NotNull private Boolean flipX;
        @NotNull private Boolean flipY;
        @NotNull @DecimalMin("0") @DecimalMax("1") private Double opacity;
        @NotNull private Boolean visible;
    }

    @Data public static class NormalizedRect {
        @NotNull @DecimalMin("0") @DecimalMax("1") private Double x;
        @NotNull @DecimalMin("0") @DecimalMax("1") private Double y;
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") private Double width;
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") private Double height;
    }

    @Data public static class ShareToken { @NotBlank @Size(max = 128) private String shareToken; }

    @Data public static class EffectiveEntitlement { private Long projectId; }

    @Data @EqualsAndHashCode(callSuper = true) public static class Ticket extends WorkspaceId { @NotBlank @Size(max = 64) private String pageId; }

    @Data @EqualsAndHashCode(callSuper = true) public static class LockAcquire extends WorkspaceId {
        @NotBlank @Size(max = 64) private String sessionId;
        @NotEmpty @Size(max = 100)
        private List<@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String> objectIds;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class LockChange extends WorkspaceId {
        @NotBlank @Size(max = 64) private String sessionId;
        @NotEmpty @Size(max = 100) private List<@Valid LockItem> locks;
    }

    @Data public static class LockItem {
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String objectId;
        @NotBlank @Size(max = 128) private String lockToken;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class TaskHistory extends WorkspaceId {
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String objectId;
        @Min(1) private Integer pageNum = 1;
        @Min(1) @Max(100) private Integer pageSize = 20;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class ActiveTasks extends WorkspaceId { }

    @Data @EqualsAndHashCode(callSuper = true) public static class SelectResult extends WorkspaceId {
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String objectId;
        @NotNull @Min(1) private Long expectedVersion;
        @NotNull private Long resultId;
        @NotBlank @Size(max = 64) private String sessionId;
        @NotBlank @Size(max = 128) private String lockToken;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String operationId;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class AdoptTextResult extends WorkspaceId {
        @NotNull @Min(1) private Long runId;
        @NotBlank @Pattern(regexp = "REPLACE|APPEND|CREATE_NODE", message = "采纳模式无效") private String mode;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String objectId;
        @NotNull @Min(0) private Long expectedVersion;
        @NotBlank @Size(max = 64) private String sessionId;
        @NotBlank @Size(max = 128) private String lockToken;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String operationId;
        @Size(max = 100) private String title;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class GenerateAudio extends GenerationBase {
        @NotNull @Valid private com.aid.media.dto.OfficialAudioGenerateRequest request;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class AudioAttachment extends WorkspaceId {
        @NotBlank private String modelCode;
        @NotBlank private String capabilityCode;
        @NotBlank @Size(max = 100) private String field;
        @NotNull @Min(1) private Long resourceId;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class AudioQuote extends WorkspaceId {
        @Size(max = 25) private List<@NotNull Long> resourceIds;
        @NotNull @Valid private com.aid.media.dto.OfficialAudioGenerateRequest request;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class GenerateImage extends GenerationBase {
        @NotNull @Valid private MediaImageGenerateRequest request;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class GenerateVideo extends GenerationBase {
        @NotNull @Valid private MediaVideoGenerateRequest request;
        @Valid private VideoTool videoTool;
    }

    /** Read-only quote for a canvas video tool; source identity is checked exactly as for submission. */
    @Data @EqualsAndHashCode(callSuper = true) public static class VideoToolQuote extends WorkspaceId {
        @Size(max = 25) private List<@NotNull Long> resourceIds;
        @NotNull @Valid private MediaVideoGenerateRequest request;
        @NotNull @Valid private VideoTool videoTool;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class VideoSubjectDetect extends WorkspaceId {
        @NotNull @Min(1) private Long frameResourceId;
    }

    /** 画布视频工具只传业务操作及源节点身份；供应商参数仍由模型能力定义校验。 */
    @Data public static class VideoTool {
        @NotBlank @Pattern(regexp = "ENHANCE|DEPTH_CAPTURE|SUBTITLE_AUTO|SUBTITLE_REGION|PORTRAIT_MASK|PORTRAIT_FOREGROUND|PORTRAIT_COMBINATION|VOICE_ONLY|BACKGROUND_ONLY|VOICE_BACKGROUND|RESHOOT|CONTINUE|SUBJECT_REMOVE|SUBJECT_MODIFY|SUBJECT_REPLACE", message = "不支持的视频工具操作，请按接口文档传入 videoTool.operation 枚举")
        private String operation;
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String sourceObjectId;
        @NotNull @Min(1) private Long sourceExpectedVersion;
        @NotNull @Min(1) private Long sourceResourceId;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class GenerationBase extends WorkspaceId {
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String objectId;
        @NotNull @Min(1) private Long expectedVersion;
        @NotBlank @Size(max = 64) private String sessionId;
        @NotBlank @Size(max = 128) private String lockToken;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") private String operationId;
        @Size(max = 25) private List<@NotNull Long> resourceIds;
    }

    @Data public static class AdminConfig {
        private Boolean collaborationEnabled;
        @Min(1) private Integer maxProjectsPerOwner;
        @Min(1) private Integer maxNodesPerWorkspace;
        @Min(1) private Integer maxMembersPerProject;
        @Min(1) private Integer maxOnlineEditors;
        @Min(1) private Integer maxConnectionsPerUser;
        @Min(1) private Integer operationBatchMax;
        @Min(1024) private Integer operationPayloadMaxBytes;
        @Min(10) private Integer lockLeaseSeconds;
        @Min(5) private Integer lockRenewSeconds;
        @Min(100) private Integer textMergeMillis;
        @Min(1) @Max(365) private Integer inviteDefaultDays;
        @Min(1) @Max(1000) private Integer inviteDefaultUses;
    }

    @Data public static class EntitlementItem {
        @NotBlank private String entitlementCode;
        @NotBlank private String valueType;
        @NotBlank private String value;
        private Boolean enabled;
    }

    @Data public static class DefaultEntitlements {
        @NotEmpty @Size(max = 100) private List<@Valid EntitlementItem> items;
    }

    @Data public static class EntitlementOverride {
        @NotNull private Long userId;
        @NotBlank private String entitlementCode;
        @NotBlank private String mode;
        private String value;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date startTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date endTime;
        @NotBlank @Size(max = 500) private String reason;
    }

    @Data @EqualsAndHashCode(callSuper = true) public static class RevokeOverride extends Id { @NotBlank @Size(max = 500) private String reason; }

    @Data public static class TerminateSession {
        @NotBlank @Size(max = 64) private String sessionId;
        @NotBlank @Size(max = 500) private String reason;
    }
}
