package com.aid.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Skill管理端请求对象集合。 */
public final class SkillAdminRequests {
    private SkillAdminRequests() { }

    @Data
    @Schema(description = "空请求")
    public static class EmptyRequest { }

    @Data
    @Schema(description = "创建 Skill 稳定身份；随后编辑草稿并发布版本")
    public static class CreateRequest {
        @Schema(description = "Skill 编码：2-64 位，以小写字母开头，仅含小写字母、数字和下划线",
                example = "storyboard_image")
        @NotBlank(message = "Skill编码必填")
        @Pattern(regexp = "[a-z][a-z0-9_]{1,63}",
                message = "Skill编码须以小写字母开头，且仅含小写字母、数字和下划线")
        private String skillCode;
        @NotBlank @Size(max = 100) private String name;
        @Size(max = 1000) private String description;
        @Size(max = 500) private String iconUrl;
        @Pattern(regexp = "PUBLIC|PRIVATE") private String visibility = "PRIVATE";
        @Pattern(regexp = "ENTRYPOINT|INTERNAL") private String invocationScope = "ENTRYPOINT";
        private Boolean cacheToDisk = false;
        @Positive private Long templateVersionId;
    }

    @Data
    @Schema(description = "Skill 管理分页请求")
    public static class PageRequest {
        @Positive
        private Long skillId;
        private Boolean includeDeleted;
        @Schema(description = "页码", example = "1")
        @Min(value = 1, message = "页码错误")
        private Integer pageNum = 1;
        @Schema(description = "每页数量", example = "20")
        @Min(value = 1, message = "页大小错误")
        @Max(value = 100, message = "页大小过大")
        private Integer pageSize = 20;
        @Schema(description = "名称或编码关键字")
        private String keyword;
        @Schema(description = "状态：0启用、1停用")
        private String status;

        @Pattern(regexp = "PLATFORM|USER", message = "归属类型错误")
        private String ownerType;

        @Positive(message = "作者ID错误")
        private Long ownerUserId;

        @Pattern(regexp = "PUBLIC|PRIVATE", message = "可见性错误")
        private String visibility;

        @Pattern(regexp = "ENTRYPOINT|INTERNAL", message = "调用范围错误")
        private String invocationScope;

        private Boolean cacheToDisk;
    }

    @Data
    @Schema(description = "ID 请求")
    public static class DetailRequest {
        @Schema(description = "Skill 或 Run ID", example = "1")
        @NotNull(message = "ID必填")
        private Long id;
    }

    @Data
    @Schema(description = "保存 Skill 稳定身份")
    public static class IdentitySaveRequest {
        @NotNull(message = "ID必填")
        @Positive(message = "ID错误")
        private Long id;

        @NotBlank(message = "名称必填")
        @Size(max = 100, message = "名称过长")
        private String name;

        @Size(max = 1000, message = "说明过长")
        private String description;

        @Size(max = 2000, message = "能力介绍过长")
        private String capabilityDescription;

        @Size(max = 500, message = "图标链接过长")
        private String iconUrl;

        @NotBlank(message = "状态必填")
        private String status;

        @Pattern(regexp = "PUBLIC|PRIVATE", message = "可见性错误")
        private String visibility;

        @Pattern(regexp = "ENTRYPOINT|INTERNAL", message = "调用范围错误")
        private String invocationScope;

        private Boolean cacheToDisk;
    }

    @Data
    @Schema(description = "Skill 状态请求")
    public static class StatusRequest {
        @Schema(description = "Skill ID")
        @NotNull(message = "ID必填")
        private Long id;
        @Schema(description = "状态：0启用、1停用")
        @NotBlank(message = "状态必填")
        private String status;
    }

    @Data
    @Schema(description = "Skill 公共发布全局配置")
    public static class PublicationSettingsRequest {
        @NotNull(message = "公开开关必填")
        private Boolean allowUserPublicSkills;
    }

    @Data
    @Schema(description = "管理员撤回用户公开 Skill")
    public static class ModerateUnpublishRequest {
        @NotNull(message = "ID必填")
        @Positive(message = "ID错误")
        private Long id;

        @NotBlank(message = "撤回原因必填")
        @Size(max = 500, message = "撤回原因过长")
        private String reason;
    }

    @Data
    @Schema(description = "Run 审计分页请求")
    public static class RunPageRequest {
        @Schema(description = "页码")
        @Min(value = 1, message = "页码错误")
        private Integer pageNum = 1;
        @Schema(description = "每页数量")
        @Min(value = 1, message = "页大小错误")
        @Max(value = 100, message = "页大小过大")
        private Integer pageSize = 20;
        @Schema(description = "Skill ID 筛选")
        private Long skillId;
        @Schema(description = "用户 ID 筛选")
        private Long userId;
        @Schema(description = "Run 状态筛选")
        private String status;
    }
}
