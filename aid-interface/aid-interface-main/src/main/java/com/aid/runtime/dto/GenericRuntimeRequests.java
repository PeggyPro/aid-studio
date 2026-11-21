package com.aid.runtime.dto;

import com.aid.generation.dto.GenerationRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GenericRuntimeRequests {
    private GenericRuntimeRequests() { }

    @Data public static class Input {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") private String clientRequestId;
        @Size(max = 100) private String modelCode;
        @Size(max = 100) private String capabilityCode;
        @Size(max = 100000) private String prompt;
        @Pattern(regexp = "TEXT|MARKDOWN|HTML") private String resultFormat = "TEXT";
        @Min(1) private Long projectId;
        @Min(0) private Long episodeId;
        @Min(1) private Long workspaceId;
        @Valid private GenerationRequests.Session session;
        @Valid private GenerationRequests.CanvasNode canvasNode;
        @Valid @Size(max = 32) private List<GenerationRequests.ResourceReference> references = new ArrayList<>();
        @Size(max = 30) private String size;
        @Min(1) @Max(100) private Integer expectedImageCount;
        @Min(1) @Max(3600) private Integer durationSeconds;
        @Size(max = 30) private String aspectRatio;
        private Boolean audio;
        private Boolean bgm;
        @Size(max = 30) private String audioType;
        @Size(max = 200) private String voiceId;
        @Size(max = 64) private Map<String, Object> parameters = new LinkedHashMap<>();
        @Size(max = 64) private Map<String, Object> input;
    }

    @Data @lombok.EqualsAndHashCode(callSuper = true)
    public static class Invoke extends Input {
        @NotBlank @Pattern(regexp = "SKILL|AGENT") private String memberType;
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9._-]{1,63}") private String memberCode;
    }

    @Data @lombok.EqualsAndHashCode(callSuper = true)
    public static class DraftTryRun extends Input {
        @NotNull @Min(1) private Long draftId;
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String draftDigest;
        @NotNull @Min(1) private Long grantId;
    }

    @Data public static class SkillRunPage {
        @Min(1) private Long skillId;
        @Min(1) private Long userId;
        @Pattern(regexp = "CREATED|RUNNING|WAITING_INPUT|SUCCEEDED|FAILED|CANCELED") private String status;
        @Min(1) private Integer pageNum = 1;
        @Min(1) @Max(100) private Integer pageSize = 20;
    }

    @Data public static class RunId {
        @NotNull @Min(1) private Long runId;
    }

    @Data public static class History {
        @Pattern(regexp = "SKILL|AGENT") private String memberType;
        @Size(max = 64) private String memberCode;
        @Min(1) private Long workspaceId;
        @Size(max = 64) private String nodeObjectId;
        @Min(1) private Integer pageNum = 1;
        @Min(1) @Max(100) private Integer pageSize = 20;
    }

    @Data public static class Clarify {
        @NotNull @Min(1) private Long runId;
        @NotBlank @Size(max = 100000) private String answer;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") private String responseKey;
    }
}
