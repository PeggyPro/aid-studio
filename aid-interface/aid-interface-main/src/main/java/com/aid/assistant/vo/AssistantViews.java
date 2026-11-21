package com.aid.assistant.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Typed assistant API projections. */
public final class AssistantViews
{
    private AssistantViews()
    {
    }

    @Data
    public static class Capabilities
    {
        private boolean sessionDeletionAvailable = true;
        private boolean modelSelectionOnDemand;
        private String transport;
        private List<String> modules;
        private List<ModuleCapability> moduleCapabilities = new ArrayList<>();
        private List<String> screenwriterModes;
        private List<String> contextKinds;
        private boolean freeCanvasAvailable;
        private boolean planningAvailable;
        private boolean executionAvailable;
        private boolean eventStream;
        private String eventStreamProtocol;
        private String planningModelCode;
        private List<SkillOption> universalSkills = new ArrayList<>();
        private boolean skillDirectoryOnDemand = true;
        private String skillDirectoryPath = "/api/user/assistant/skills/page";
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModuleCapability
    {
        private String moduleCode;
        private String operationMode;
        private Boolean engineAvailable;
        private Boolean eventStream;
        private String engineUnavailableReasonCode;
        private String engineUnavailableReason;
        private boolean planningAvailable;
        private boolean executionAvailable;
        private String planningUnavailableReasonCode;
        private String planningUnavailableReason;
        private String executionUnavailableReasonCode;
        private String executionUnavailableReason;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SkillOption
    {
        private String code;
        private String name;
        private String description;
        private String versionId;
        private String packageDigest;
        private String outputKind;
        private String executionRoute;
    }

    @Data
    public static class ModelSelection
    {
        private String modelType;
        private String modelCode;
        private String modelName;
        private String providerName;
        private String providerCode;
    }

    @Data
    public static class Session
    {
        private String transport;
        private String sessionId;
        private String clientSessionId;
        private String moduleCode;
        private String mode;
        private String title;
        private String projectId;
        private String episodeId;
        private String workspaceId;
        private String nodeObjectId;
        private String previzProjectId;
        private Boolean reasoningEnabled;
        private String reasoningLevel;
        private Boolean showReasoning;
        private String status;
        private long stateRevision;
        private long lastMessageSeq;
        private long lastEventSeq;
        private String activeRunId;
        private String lastActiveTime;
    }

    @Data
    public static class Page<T>
    {
        private List<T> items = new ArrayList<>();
        private String nextCursor;
    }

    @Data
    public static class SessionDetail
    {
        private Session session;
        private List<Message> messages = new ArrayList<>();
        private boolean hasEarlierMessages;
        private Run activeRun;
        private InputRequest inputRequest;
        private List<InputRequest> inputHistory = new ArrayList<>();
        private boolean inputHistoryHasMore;
        private List<Artifact> conversationArtifacts = new ArrayList<>();
        private boolean conversationArtifactsHasMore;
        private Plan plan;
        private List<Step> steps = new ArrayList<>();
        private boolean stepsHasMore;
        private List<Task> tasks = new ArrayList<>();
        private List<Artifact> artifacts = new ArrayList<>();
        private boolean artifactsHasMore;
        private Approval approval;
        private List<Activity> activities = new ArrayList<>();
        private boolean activitiesHasMore;
        private long lastEventSeq;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Activity
    {
        private String activityId;
        private String runId;
        private String outerStepId;
        private String innerStepId;
        private String skillRunId;
        private String streamId;
        private String activityKind;
        private String safeSummary;
        private String status;
        private String occurredAt;
        private Long sourceEventSeq;
        private String stepKind;
        /** Current persisted step state; event status remains the historical event state. */
        private String executionStatus;
        private String executionSummary;
        private String executionErrorCode;
        private String operationKind;
        private String toolCode;
        private String interactionId;
        private String interactionKind;
    }

    @Data
    public static class Plan
    {
        private long revision;
        private String status;
        private String definitionDigest;
        private String contextDigest;
        private String title;
        private String summary;
        private String goal;
        private List<String> assumptions = new ArrayList<>();
        private List<String> deliverables = new ArrayList<>();
        private List<PlanStep> steps = new ArrayList<>();
        private List<PlanCriterion> completionCriteria = new ArrayList<>();
        private boolean replanRequired;
        private List<String> allowedActions = new ArrayList<>();
    }

    @Data
    public static class PlanStep
    {
        private String stableStepId;
        private Integer position;
        private String operationKind;
        private String executorKind;
        private String bindingCode;
        private Integer maxAttempts;
        private String title;
        private String summary;
        private List<String> dependsOn = new ArrayList<>();
        private List<String> inputArtifactRefs = new ArrayList<>();
        private List<PlanInputArtifactBinding> inputArtifactBindings = new ArrayList<>();
        private List<String> resolvedInputArtifactRefs = new ArrayList<>();
        private boolean inputsResolved;
        private String inputResolutionErrorCode;
    }

    @Data
    public static class PlanInputArtifactBinding
    {
        private String sourceStableStepId;
        private String logicalArtifactId;
        private String artifactType;
    }

    @Data
    public static class PlanCriterion
    {
        private String criterionId;
        private String criterionKind;
        private String description;
    }

    @Data
    public static class Step
    {
        private String stepId;
        private String createdAt;
        private String stableStepId;
        private long planRevision;
        private Integer position;
        private int attemptNo;
        private String stepKind;
        private String operationKind;
        private String toolCode;
        private String status;
        private String approvalId;
        private String mediaTaskId;
        private String providerAcceptanceState;
        private List<String> outputArtifactRefs = new ArrayList<>();
        private String errorCode;
        private String safeMessage;
        private List<AssistantTargetViews.TargetEffect> effects = new ArrayList<>();
    }

    @Data
    public static class RunStepPageScope
    {
        private String sessionId;
        private long observedSessionRevision;
        private long observedLastEventSeq;
        private String runId;
        private long observedRunRevision;
        private String runStatus;
        private String nextAction;
    }

    @Data
    public static class RunStepPage
    {
        private RunStepPageScope scope;
        private List<Step> items = new ArrayList<>();
        private boolean hasMore;
        private String nextCursor;
    }

    @Data
    public static class Task
    {
        private String taskId;
        private String stepId;
        private String mediaType;
        private String modelCode;
        private String status;
        private String billingStatus;
        private com.aid.media.dto.TaskEtaVO eta;
        private Integer queuePosition;
        private String progressSource;
        private Integer progress;
        private String stage;
        private String errorMessage;
        private String title;
        private Long outputDurationSeconds;
        private List<String> allowedActions = new ArrayList<>();
    }

    @Data
    public static class Artifact
    {
        private String artifactId;
        private String presentationRole;
        private String ref;
        private String type;
        private long revision;
        private String status;
        private String reviewStatus;
        private String summary;
        private String producerRunId;
        private String producerStepId;
        private String createdAt;
        private AssistantTargetViews.TargetEffect latestTargetEffect;
    }

    @Data
    public static class Approval
    {
        private String approvalId;
        private long approvalRevision;
        private String scopeKind;
        private String status;
        private long planRevision;
        private String stepId;
        private String operationKind;
        private String toolCode;
        private String skillCode;
        private String modelCode;
        private List<String> capabilityCodes = new ArrayList<>();
        private List<String> mediaTypes = new ArrayList<>();
        private String title;
        private String summary;
        private String maxCharge;
        private String quoteId;
        private String quotedUpperBound;
        private String estimatedAmount;
        private Integer precisionScale;
        private boolean hardUpperBoundAvailable;
        private String currencyUnit;
        private String expiresAt;
        private Object targetRefs;
        private List<String> allowedActions = new ArrayList<>();
    }

    @Data
    public static class Message
    {
        private String messageId;
        private long seq;
        private String role;
        private String messageKind;
        private String status;
        private String runtimeRunId;
        private String turnId;
        private String content;
        private List<MessagePart> parts = new ArrayList<>();
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Object flowScreenplayDraft;
        private List<MessageReference> references = new ArrayList<>();
        private String createdAt;
    }

    @Data
    public static class MessageReference
    {
        private int ordinal;
        private String kind;
        private String label;
        private String status;
        private String workspaceId;
        private String objectId;
        private String resourceId;
        private String mediaKind;
        private String previewUrl;
    }

    @Data
    public static class MessagePart
    {
        private int partSeq;
        private String type;
        private String text;
    }

    @Data
    public static class MessageSent
    {
        private String messageId;
        private String turnId;
        private String runId;
        private long sessionRevision;
        private long runRevision;
        private String status;
        private String nextAction;
        private String requestDigest;
        private long lastEventSeq;
    }

    @Data
    public static class ContextRefreshed
    {
        private String runId;
        private long runRevision;
        private String contextDigest;
        private List<?> targetRefs = new ArrayList<>();
        private String status;
        private String nextAction;
        private long lastEventSeq;
    }

    @Data
    public static class Run
    {
        private boolean automaticTextExecution;
        private String automaticTextFailure;
        private List<ModelSelection> selectedModels = new ArrayList<>();
        private String runId;
        private String executionPreference;
        private String selectedSkillCode;
        private long revision;
        private String status;
        private String pauseReason;
        private String resumeStatus;
        private boolean stopRequested;
        private String nextAction;
        private List<String> allowedActions = new ArrayList<>();
        private List<String> activeTaskIds = new ArrayList<>();
        private long lastEventSeq;
    }

    @Data
    public static class InputRequest
    {
        private String requestId;
        private String runId;
        private String requestKey;
        private int roundNo;
        private String schemaDigest;
        private String contextDigest;
        private long planRevision;
        private String status;
        private String createdAt;
        private String completedAt;
        private List<Question> questions = new ArrayList<>();
        private List<Draft> drafts = new ArrayList<>();
        private InputView view;
    }

    @Data
    public static class Question
    {
        private String questionId;
        private String type;
        private String title;
        private String description;
        private boolean required;
        private boolean allowFreeText;
        private Integer minSelections;
        private Integer maxSelections;
        private List<QuestionOption> options = new ArrayList<>();
    }

    @Data
    public static class QuestionOption
    {
        private String optionId;
        private String label;
        private String description;
        private boolean exclusive;
        private String controlAction;
    }

    @Data
    public static class Draft
    {
        private String requestId;
        private String questionId;
        private long draftRevision;
        private List<String> selectedOptionIds = new ArrayList<>();
        private String freeText;
        private String answerState;
        private int unansweredCount;
        private long runRevision;
    }

    @Data
    public static class InputView
    {
        private String requestId;
        private String cursorQuestionId;
    }

    @Data
    public static class InputSubmitted
    {
        private String requestId;
        private String acceptedDigest;
        private String runId;
        private long runRevision;
        private String status;
        private String nextAction;
        private long lastEventSeq;
    }

    @Data
    public static class EventPage
    {
        private List<Event> items = new ArrayList<>();
        private long scannedThroughSeq;
        private long lastEventSeq;
        private boolean hasMore;
    }

    @Data
    public static class Event
    {
        private String eventId;
        private String sessionId;
        private long seq;
        private String type;
        private String runId;
        private String entityId;
        private Long entityRevision;
        private String causationId;
        private String occurredAt;
        private Object data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error
    {
        private String errorCode;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Object details;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevisionDetails
    {
        private long suppliedRevision;
        private long currentRevision;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftConflictDetails
    {
        private String questionId;
        private Draft currentDraft;
    }
}
