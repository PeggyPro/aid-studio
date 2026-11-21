package com.aid.assistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Typed assistant API requests. */
public final class AssistantRequests
{
    private AssistantRequests()
    {
    }

    private static final String KEY_PATTERN = "[A-Za-z0-9._:-]{1,64}";

    @Data
    public static class Capabilities
    {
        @NotBlank
        @Pattern(regexp = "MAIN|CANVAS")
        private String transport;
    }

    @Data
    public static class SessionOpen
    {
        @Pattern(regexp = "MAIN|CANVAS")
        private String transport;
        @NotBlank
        @Pattern(regexp = "asst:[A-Za-z0-9._:-]{1,59}")
        private String clientSessionId;
        @NotBlank
        @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}")
        private String moduleCode;
        @Size(max = 100)
        @Pattern(regexp = "[A-Za-z0-9_-]{1,100}")
        private String previzProjectId;
        @Pattern(regexp = "CRAFT|ADAPT")
        private String mode;
        @Min(1)
        private Long projectId;
        @Min(0)
        private Long episodeId;
        @Min(1)
        private Long workspaceId;
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
        private String nodeObjectId;
        @NotBlank
        @Size(max = 100)
        private String title;
        private Boolean reasoningEnabled;
        @Pattern(regexp = "minimal|low|medium|high|xhigh|max|ultra")
        private String reasoningLevel;
        private Boolean showReasoning;
    }

    @Data
    public static class SessionPage
    {
        @Pattern(regexp = "MAIN|CANVAS")
        private String transport;
        private Long cursor;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
        @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}")
        private String moduleCode;
        @Size(max = 100)
        @Pattern(regexp = "[A-Za-z0-9_-]{1,100}")
        private String previzProjectId;
        @Min(1)
        private Long workspaceId;
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
        private String nodeObjectId;
    }

    @Data
    public static class SessionDetail
    {
        @NotNull
        private Long sessionId;
        private Long beforeMessageSeq;
        @Min(1)
        @Max(100)
        private Integer messageLimit = 50;
        @Min(1)
        @Max(100)
        private Integer inputHistoryLimit = 20;
        @Min(1)
        @Max(200)
        private Integer artifactHistoryLimit = 100;
    }

    @Data
    public static class MessageSend
    {
        @NotNull
        private Long sessionId;
        @NotBlank
        @Pattern(regexp = KEY_PATTERN)
        private String clientRequestId;
        @NotNull
        @Min(0)
        private Long expectedSessionRevision;
        @Min(0)
        private Long expectedRunRevision;
        @Valid
        @NotEmpty
        @Size(max = 20)
        private List<MessagePart> parts = new ArrayList<>();
        @Valid
        private List<ContextRef> contextRefs = new ArrayList<>();
        @Pattern(regexp = "MANUAL|AUTO")
        private String executionPreference = "MANUAL";
        @Valid
        private CanvasBudget canvasBudget;
        @Size(max = 100)
        @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]*")
        private String selectedSkillCode;
        @Size(max = 100)
        private String selectedTextModelCode;
        @Valid
        @Size(max = 2)
        private List<@NotNull ModelSelection> selectedModels = new ArrayList<>();
    }

    @Data
    public static class CanvasBudget
    {
        @NotBlank
        @Pattern(regexp = "[0-9]{1,10}(\\.[0-9]{1,4})?")
        private String absoluteMaxCharge;
        @NotBlank
        @Pattern(regexp = "[0-9]{1,10}(\\.[0-9]{1,4})?")
        private String perCallMaxCharge;
    }

    @Data
    public static class ModelSelection
    {
        @NotBlank
        @Pattern(regexp = "image|video")
        private String modelType;
        @NotBlank
        @Size(max = 100)
        private String modelCode;
    }

    @Data
    public static class MessagePart
    {
        @NotBlank
        @Pattern(regexp = "TEXT")
        private String type;
        @NotBlank
        @Size(max = 100000)
        private String text;
    }

    @Data
    public static class ContextRef
    {
        @NotBlank
        @Pattern(regexp = "FREE_CANVAS|CANVAS_RESOURCE|FLOW_ENTITY|SCRIPT|DOCUMENT|PREVIZ_PROJECT|PREVIZ_IMAGE|PREVIZ_EDITOR")
        private String kind;
        @Size(max = 100)
        private String previzProjectId;
        @Pattern(regexp = "[a-f0-9]{64}")
        private String sourceVersion;
        private Long workspaceId;
        @Min(1)
        private Long resourceId;
        @Size(max = 64)
        private String objectId;
        @Size(max = 64)
        private String version;
        private Long projectId;
        private Long episodeId;
        @Size(max = 40)
        private String entityType;
        @Size(max = 64)
        private String entityId;
        private Long scriptId;
        @Min(1)
        private Long documentId;
        @Min(1)
        private Long documentSessionId;
        @Size(max = 64)
        private String contentHash;
        @Valid
        private ScriptSelection selection;
        @Size(max = 200)
        private String reason;
    }

    @Data
    public static class ContextRefresh
    {
        @NotNull
        private Long runId;
        @NotNull
        @Min(0)
        private Long expectedRunRevision;
        @NotBlank
        @Pattern(regexp = KEY_PATTERN)
        private String clientRequestId;
        @NotNull
        private List<ContextRef> targetRefs = new ArrayList<>();
        /** Refresh original identities instead of replacing them with the current selection. */
        private Boolean preserveExistingTargets;
    }

    @Data
    public static class EffectResolve
    {
        @NotNull
        private Long sessionId;
        @NotBlank
        @Pattern(regexp = "(?:runtime-step|artifact-adoption):[1-9][0-9]{0,18}")
        private String effectId;
    }

    @Data
    public static class ScriptSelection
    {
        @NotNull
        @Min(0)
        private Integer start;
        @NotNull
        @Min(1)
        private Integer end;
        @NotBlank
        @Size(max = 64)
        private String selectedTextHash;
    }

    @Data
    public static class DraftSave
    {
        @NotNull
        private Long requestId;
        @NotBlank
        @Size(max = 64)
        private String questionId;
        @NotNull
        @Min(0)
        private Long expectedDraftRevision;
        @Size(max = 100)
        private List<@Size(max = 64) String> selectedOptionIds = new ArrayList<>();
        @Size(max = 10000)
        private String freeText;
        @NotBlank
        @Pattern(regexp = KEY_PATTERN)
        private String clientOperationId;
    }

    @Data
    public static class ViewSave
    {
        @NotNull
        private Long requestId;
        @Size(max = 64)
        private String cursorQuestionId;
    }

    @Data
    public static class InputSubmit
    {
        @NotNull
        private Long requestId;
        @NotBlank
        @Pattern(regexp = KEY_PATTERN)
        private String responseKey;
        @NotNull
        @Min(0)
        private Long expectedRunRevision;
        @NotBlank
        @Size(max = 64)
        private String schemaDigest;
        @NotBlank
        @Size(max = 64)
        private String contextDigest;
        @Valid
        @NotEmpty
        @Size(max = 100)
        private List<AnswerRef> answers = new ArrayList<>();
    }

    @Data
    public static class AnswerRef
    {
        @NotBlank
        @Size(max = 64)
        private String questionId;
        @NotNull
        @Min(1)
        private Long draftRevision;
    }

    @Data
    public static class InputSuspend
    {
        @NotNull
        private Long requestId;
        @NotBlank
        @Pattern(regexp = KEY_PATTERN)
        private String clientRequestId;
        @NotNull
        @Min(0)
        private Long expectedRunRevision;
        @Valid
        @Size(max = 100)
        private List<DraftPatch> dirtyDrafts = new ArrayList<>();
    }

    @Data
    public static class DraftPatch
    {
        @NotBlank
        @Size(max = 64)
        private String questionId;
        @NotNull
        @Min(0)
        private Long expectedDraftRevision;
        @Size(max = 100)
        private List<@Size(max = 64) String> selectedOptionIds = new ArrayList<>();
        @Size(max = 10000)
        private String freeText;
        @NotBlank
        @Pattern(regexp = KEY_PATTERN)
        private String clientOperationId;
    }

    @Data
    public static class RunControl
    {
        @NotNull
        private Long runId;
        @NotNull
        @Min(0)
        private Long expectedRevision;
        @NotBlank
        @Pattern(regexp = KEY_PATTERN)
        private String clientRequestId;
    }

    @Data
    public static class RunDetail
    {
        @NotNull
        private Long runId;
    }

    @Data
    public static class RunStepPage
    {
        @NotNull
        @Min(1)
        private Long runId;
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        private String beforeStepId;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    public static class EventPage
    {
        @NotNull
        private Long sessionId;
        @NotNull
        @Min(0)
        private Long afterSeq;
        @Min(1)
        @Max(200)
        private Integer limit = 100;
    }

    @Data
    public static class InputRequestCreate
    {
        @NotNull
        private Long runId;
        @NotBlank
        @Pattern(regexp = KEY_PATTERN)
        private String requestKey;
        @NotNull
        @Min(0)
        private Long expectedRunRevision;
        @NotBlank
        @Size(max = 64)
        private String contextDigest;
        @Valid
        @NotEmpty
        @Size(max = 100)
        private List<Question> questions = new ArrayList<>();
    }

    @Data
    public static class Question
    {
        @NotBlank
        @Size(max = 64)
        private String questionId;
        @NotBlank
        @Pattern(regexp = "SINGLE|MULTI|TEXT")
        private String type;
        @NotBlank
        @Size(max = 300)
        private String title;
        @Size(max = 1000)
        private String description;
        private boolean required;
        private boolean allowFreeText;
        @Min(0)
        private Integer minSelections;
        @Min(0)
        private Integer maxSelections;
        @Valid
        @Size(max = 100)
        private List<QuestionOption> options = new ArrayList<>();
    }

    @Data
    public static class QuestionOption
    {
        @NotBlank
        @Size(max = 64)
        private String optionId;
        @NotBlank
        @Size(max = 200)
        private String label;
        @Size(max = 500)
        private String description;
        private boolean exclusive;
        /** Business options continue or omit control; only the server-owned option may pause. */
        @Pattern(regexp = "CONTINUE_RUN|PAUSE_RUN")
        private String controlAction;
    }
}
