package com.aid.runtime.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** Admin requests for immutable Runtime Agent packages. */
public final class RuntimeAgentRequests
{
    public static final String ADMIN_FAMILY_HEADER = "X-Aid-Runtime-Agent-Family";
    public static final String ASSISTANT_LOOP = "ASSISTANT_LOOP";
    public static final String ASSISTANT_LOOP_V1 = "assistant-loop/v1";
    public static final String ASSISTANT_LOOP_NATIVE_V1 = "assistant-loop/native-v1";
    public static final String STRICT_LOCAL_JSON = "STRICT_LOCAL_JSON";
    public static final String NATIVE_JSON_SCHEMA = "NATIVE_JSON_SCHEMA";
    public static final String NATIVE_TOOL_STREAM = "NATIVE_TOOL_STREAM";
    public static final int MAX_SKILL_BINDINGS = 64;
    public static final int MAX_TOOL_BINDINGS = 65;

    private RuntimeAgentRequests()
    {
    }

    /** Constraints that must hold before a draft can be validated or published. */
    public interface PublishReady { }

    @Data
    public static class Page
    {
        @Min(1) private Integer pageNum = 1;
        @Min(1) @Max(100) private Integer pageSize = 20;
        @Size(max = 100) private String keyword;
        @Pattern(regexp = "0|1") private String status;
        private Boolean includeDeleted = false;
    }

    @Data
    public static class Id
    {
        @NotNull @Positive private Long id;
    }

    @Data
    public static class Create
    {
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9._-]{1,63}") private String agentCode;
        @NotBlank @Size(max = 100) private String name;
        @Size(max = 1000) private String description;
        @Size(max = 500) private String iconUrl;
        @Pattern(regexp = "PUBLIC|PRIVATE") private String visibility = "PRIVATE";
    }

    @Data
    public static class Identity
    {
        @NotNull @Positive private Long id;
        @NotBlank @Size(max = 100) private String name;
        @Size(max = 1000) private String description;
        @Size(max = 500) private String iconUrl;
        @Pattern(regexp = "PUBLIC|PRIVATE") private String visibility;
    }

    @Data
    public static class Status
    {
        @NotNull @Positive private Long id;
        @NotBlank @Pattern(regexp = "0|1") private String status;
    }

    @Data
    public static class VersionPage
    {
        @NotNull @Positive private Long agentId;
        @Min(1) private Integer pageNum = 1;
        @Min(1) @Max(100) private Integer pageSize = 20;
    }

    @Data
    public static class DraftDetail
    {
        @NotNull @Positive private Long agentId;
        @Positive private Long baseVersionId;
    }

    @Data
    public static class SkillBinding
    {
        @NotNull @Positive private Long skillId;
        @NotNull @Positive private Long skillVersionId;
        @NotBlank @Size(max = 64) private String skillCode;
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String packageDigest;
    }

    @Data
    public static class ToolBinding
    {
        @NotBlank @Size(max = 100) private String toolCode;
        @NotBlank @Size(max = 32) private String contractVersion;
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String schemaDigest;
    }

    @Data
    public static class ResponsePolicy
    {
        @NotBlank(groups = PublishReady.class)
        @Pattern(regexp = "|STRICT_LOCAL_JSON|NATIVE_JSON_SCHEMA|NATIVE_TOOL_STREAM")
        private String mode = NATIVE_TOOL_STREAM;
        @NotNull @Min(0) @Max(2) private Integer maxRepairAttempts = 0;
        /** Omitted/null HTTP values preserve the trusted draft's existing selector. */
        // Format only: supported versions are governed by RuntimeAgentAdminService/NativeAssistantBindingPolicy.
        @Size(max = 64)
        @Pattern(regexp = "assistant-native-bindings/v[1-9][0-9]*")
        private String nativeToolBindingsSchemaVersion;
    }

    @Data
    public static class PackageResource
    {
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,99}") private String resourceKey;
        @NotBlank @Pattern(regexp = "references/[a-z0-9][a-z0-9._/-]{0,180}") private String filePath;
        @NotBlank @Size(max = 100) private String mimeType = "text/markdown";
        @NotBlank @Size(max = 102400) private String content;
        @Size(max = 8000) private String routeJson = "{}";
    }

    @Data
    public static class DraftPayload
    {
        @NotNull @Positive private Long agentId;
        @Positive private Long baseVersionId;
        @NotBlank(groups = PublishReady.class) @Size(max = 100000) private String mainMarkdown;
        @NotBlank(groups = PublishReady.class) @Size(max = 100000) private String systemPrompt;
        @NotNull @Valid @Size(max = 64)
        private List<@NotNull PackageResource> resources = new ArrayList<>();
        /** Published runtime mirror of systemPrompt; retained for old API readers. */
        @NotBlank(groups = PublishReady.class) @Size(max = 100000) private String instruction;
        @NotBlank(groups = PublishReady.class)
        @Pattern(regexp = "|MODEL_PLAN|SKILL_ORCHESTRATOR|ASSISTANT_LOOP")
        private String executorType = ASSISTANT_LOOP;
        @NotBlank(groups = PublishReady.class) @Size(max = 40)
        @Pattern(regexp = "|assistant-loop/v1|assistant-loop/native-v1")
        private String protocolVersion = ASSISTANT_LOOP_NATIVE_V1;
        @NotBlank(groups = PublishReady.class) @Size(max = 100) private String modelCapabilityCode;
        @NotBlank(groups = PublishReady.class) @Size(max = 100) private String defaultModelCode;
        @NotNull @Size(max = 20) @Size(min = 1, groups = PublishReady.class)
        private List<@NotBlank @Size(max = 100) String> selectableModelCodes = new ArrayList<>();
        @Min(0) @Max(200) private Integer temperatureBasisPoints = 70;
        @Min(1) @Max(64) private Integer maxTurns = 12;
        @Min(1) @Max(64) private Integer maxToolCalls = 12;
        @NotBlank(groups = PublishReady.class) @Pattern(regexp = "|TURN_BOUNDARY|TOOL_BOUNDARY") private String checkpointPolicy = "TOOL_BOUNDARY";
        @NotBlank(groups = PublishReady.class) @Size(max = 100000) private String inputSchemaJson = "{\"type\":\"object\"}";
        @NotBlank(groups = PublishReady.class) @Size(max = 100000) private String outputSchemaJson = "{\"type\":\"object\"}";
        @NotNull(groups = PublishReady.class) @Valid private ResponsePolicy responsePolicy = new ResponsePolicy();
        @NotNull @Valid @Size(max = MAX_SKILL_BINDINGS, message = "Skill绑定最多64项")
        private List<@NotNull SkillBinding> skillBindings = new ArrayList<>();
        @NotNull @Valid @Size(max = MAX_TOOL_BINDINGS, message = "Tool绑定最多65项（含通用历史读取）")
        private List<@NotNull ToolBinding> toolBindings = new ArrayList<>();
        /** Optional formal-package version; when present publish must use this immutable version. */
        @Pattern(regexp = "[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")
        private String packageVersionCode;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DraftSave extends DraftPayload
    {
        @Positive private Long draftId;
        @Pattern(regexp = "[0-9a-f]{64}") private String draftDigest;
    }

    @Data
    public static class Publish
    {
        @NotNull @Positive private Long draftId;
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String draftDigest;
        @NotBlank @Pattern(regexp = "[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")
        private String versionCode;
    }

    @Data
    public static class Activate
    {
        @NotNull @Positive private Long agentId;
        @NotNull @Positive private Long versionId;
        @Positive private Long expectedCurrentVersionId;
    }

    @Data
    public static class BuiltinImport
    {
        @NotBlank @Pattern(regexp = "universal_canvas_agent|story_script_agent|spatial_previs_agent")
        private String agentCode;
        private Boolean replaceExistingDraft = false;
        @Positive private Long expectedDraftId;
        @Pattern(regexp = "[0-9a-f]{64}") private String expectedDraftDigest;
    }

    @Data
    public static class ModuleBindingActivate
    {
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") private String moduleCode;
        @NotNull @Positive private Long agentId;
        @NotNull @Positive private Long versionId;
        @NotNull @Min(0) private Long expectedBindingRevision;
    }

}
