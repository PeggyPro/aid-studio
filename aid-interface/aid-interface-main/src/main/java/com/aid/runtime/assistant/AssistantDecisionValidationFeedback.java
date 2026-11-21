package com.aid.runtime.assistant;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Server-owned, bounded description of a deterministic Assistant decision validation failure.
 * It deliberately excludes the rejected payload and exception text.
 */
public record AssistantDecisionValidationFeedback(
        String schemaVersion,
        Reason reason,
        ActionFamily actionFamily,
        SafePath safePath,
        ExpectedShape expectedShape,
        ObservedType observedType,
        String trustedContractDigest,
        List<Issue> issues)
{
    public static final String SCHEMA_VERSION = "assistant-decision-validation-feedback/v1";
    public static final int MAX_ISSUES = 4;
    public static final int MAX_UTF8_BYTES = 4096;
    private static final Set<String> SAFE_FIELDS = Set.of("description");

    public AssistantDecisionValidationFeedback
    {
        if (!SCHEMA_VERSION.equals(schemaVersion) || reason == null || actionFamily == null
                || safePath == null || expectedShape == null || observedType == null
                || trustedContractDigest == null
                || !trustedContractDigest.matches("^[0-9a-f]{64}$"))
        {
            throw new IllegalArgumentException("Invalid Assistant decision validation feedback");
        }
        issues = issues == null ? List.of() : List.copyOf(issues);
        if (issues.size() > MAX_ISSUES)
            throw new IllegalArgumentException("Too many Assistant decision validation issues");
        validateReason(reason, actionFamily, safePath, expectedShape, observedType, issues);
        String canonical = canonicalJson(schemaVersion, reason, actionFamily, safePath,
                expectedShape, observedType, trustedContractDigest, issues);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES)
            throw new IllegalArgumentException("Assistant decision validation feedback is too large");
    }

    public static AssistantDecisionValidationFeedback planStepsArray(String trustedContractDigest,
                                                                       ObservedType observedType)
    {
        return create(Reason.PLAN_STEPS_ARRAY_REQUIRED, ActionFamily.PLAN, SafePath.PLAN_STEPS,
                ExpectedShape.ARRAY, observedType, trustedContractDigest, List.of());
    }

    public static AssistantDecisionValidationFeedback chatMessageObjectRequired(
            String trustedContractDigest, ObservedType observedType)
    {
        return create(Reason.CHAT_MESSAGE_OBJECT_REQUIRED, ActionFamily.CHAT, SafePath.CHAT_MESSAGE,
                ExpectedShape.OBJECT, observedType, trustedContractDigest, List.of());
    }

    public static AssistantDecisionValidationFeedback planArrayRequired(
            String trustedContractDigest, SafePath path, ObservedType observedType)
    {
        return create(Reason.PLAN_ARRAY_REQUIRED, ActionFamily.PLAN, path,
                ExpectedShape.ARRAY, observedType, trustedContractDigest, List.of());
    }

    public static AssistantDecisionValidationFeedback planStepUnknownFields(String trustedContractDigest,
                                                                             Set<String> unknownFields)
    {
        List<Issue> safeIssues = unknownFields == null ? List.of() : unknownFields.stream()
                .sorted().limit(MAX_ISSUES).map(AssistantDecisionValidationFeedback::unknownField).toList();
        return create(Reason.PLAN_STEP_UNKNOWN_FIELD, ActionFamily.PLAN, SafePath.PLAN_STEP,
                ExpectedShape.DECLARED_FIELDS_ONLY, ObservedType.OBJECT,
                trustedContractDigest, safeIssues);
    }

    public static AssistantDecisionValidationFeedback planInputOutputNotDeclared(
            String trustedContractDigest)
    {
        return create(Reason.PLAN_INPUT_OUTPUT_NOT_DECLARED, ActionFamily.PLAN,
                SafePath.PLAN_INPUT_ARTIFACT_BINDING, ExpectedShape.DECLARED_LOGICAL_OUTPUT,
                ObservedType.UNDECLARED_REFERENCE, trustedContractDigest, List.of());
    }

    public static AssistantDecisionValidationFeedback planDependencyNotDeclared(String digest)
    {
        return create(Reason.PLAN_DEPENDENCY_NOT_DECLARED, ActionFamily.PLAN,
                SafePath.PLAN_STEPS, ExpectedShape.CURRENT_PLAN_STEP_DEPENDENCIES,
                ObservedType.UNDECLARED_REFERENCE, digest, List.of());
    }

    public static AssistantDecisionValidationFeedback planCompletionEvidenceUnsupported(String digest)
    {
        return create(Reason.PLAN_COMPLETION_EVIDENCE_UNSATISFIABLE, ActionFamily.PLAN,
                SafePath.PLAN_COMPLETION_CRITERIA,
                ExpectedShape.PLAN_PRODUCED_EVIDENCE_OR_DIRECT_READ_ONLY_QUERY,
                ObservedType.MISSING_PREREQUISITE, digest, List.of());
    }

    public static AssistantDecisionValidationFeedback planRepeatsCompletedOperation(String digest)
    {
        return create(Reason.PLAN_REPEATS_COMPLETED_OPERATION, ActionFamily.PLAN,
                SafePath.PLAN_STEPS, ExpectedShape.REMAINING_OPERATIONS_ONLY,
                ObservedType.UNDECLARED_REFERENCE, digest, List.of());
    }

    public static AssistantDecisionValidationFeedback planArtifactOutputRequired(String digest)
    {
        return create(Reason.PLAN_ARTIFACT_OUTPUT_REQUIRED, ActionFamily.PLAN,
                SafePath.PLAN_OUTPUT_ARTIFACT_IDS, ExpectedShape.SINGLE_LOGICAL_ARTIFACT_ID,
                ObservedType.MISSING_PREREQUISITE, digest, List.of());
    }

    public static AssistantDecisionValidationFeedback planBillableOutputRequired(String digest)
    {
        return create(Reason.PLAN_BILLABLE_OUTPUT_REQUIRED, ActionFamily.PLAN,
                SafePath.PLAN_OUTPUT_ARTIFACT_IDS, ExpectedShape.SINGLE_LOGICAL_ARTIFACT_ID,
                ObservedType.MISSING_PREREQUISITE, digest, List.of());
    }

    public static AssistantDecisionValidationFeedback planInputsNotReady(
            String trustedContractDigest)
    {
        return create(Reason.PLAN_INPUTS_NOT_READY, ActionFamily.PLAN,
                SafePath.PLAN_INPUT_ARTIFACT_BINDING, ExpectedShape.RESOLVED_PLAN_INPUTS,
                ObservedType.UNRESOLVED_REFERENCE, trustedContractDigest, List.of());
    }

    /** A valid proposed plan omits a required current-plan read or its dependency edges. */
    public static AssistantDecisionValidationFeedback planReadPrerequisitesRequired(
            String trustedContractDigest)
    {
        return create(Reason.PLAN_READ_PREREQUISITES_REQUIRED, ActionFamily.PLAN,
                SafePath.PLAN_STEPS, ExpectedShape.CURRENT_PLAN_READ_DEPENDENCIES,
                ObservedType.MISSING_PREREQUISITE, trustedContractDigest, List.of());
    }

    public static AssistantDecisionValidationFeedback nativeArgumentsObjectRequired(
            ActionFamily actionFamily, String trustedContractDigest, ObservedType observedType)
    {
        return create(Reason.NATIVE_ARGUMENTS_OBJECT_REQUIRED, actionFamily,
                SafePath.NATIVE_ARGUMENTS, ExpectedShape.OBJECT, observedType,
                trustedContractDigest, List.of());
    }

    /** Syntax feedback contains no rejected arguments or model-provided error text. */
    public static AssistantDecisionValidationFeedback nativeArgumentsJsonInvalid(
            ActionFamily actionFamily, String trustedContractDigest)
    {
        return create(Reason.NATIVE_ARGUMENTS_JSON_INVALID, actionFamily,
                SafePath.NATIVE_ARGUMENTS, ExpectedShape.OBJECT, ObservedType.INVALID_JSON,
                trustedContractDigest, List.of());
    }

    public static AssistantDecisionValidationFeedback argumentSchemaMismatch(String trustedContractDigest)
    {
        return create(Reason.ARGUMENT_SCHEMA_MISMATCH, ActionFamily.TOOL,
                SafePath.TOOL_ARGUMENTS, ExpectedShape.TRUSTED_SCHEMA,
                ObservedType.SCHEMA_MISMATCH, trustedContractDigest, List.of());
    }

    /** A fixed Native ASK input contract failed before business decision persistence. */
    public static AssistantDecisionValidationFeedback nativeAskSchemaMismatch(String trustedContractDigest)
    {
        return create(Reason.NATIVE_CONTROL_SCHEMA_MISMATCH, ActionFamily.ASK,
                SafePath.NATIVE_ARGUMENTS, ExpectedShape.TRUSTED_SCHEMA,
                ObservedType.SCHEMA_MISMATCH, trustedContractDigest, List.of());
    }

    /** The immutable native catalog did not expose the returned function name. */
    public static AssistantDecisionValidationFeedback nativeFunctionNameNotExposed(String catalogDigest)
    {
        return create(Reason.NATIVE_FUNCTION_NAME_NOT_EXPOSED, ActionFamily.TOOL,
                SafePath.NATIVE_FUNCTION_NAME, ExpectedShape.EXPOSED_FUNCTION_NAME,
                ObservedType.UNDECLARED_FUNCTION_NAME, catalogDigest, List.of());
    }

    public static AssistantDecisionValidationFeedback nativeControlText(String catalogDigest)
    {
        return create(Reason.NATIVE_CONTROL_TEXT_REQUIRES_TOOL_CALL, ActionFamily.TOOL,
                SafePath.NATIVE_CONTENT, ExpectedShape.NATIVE_TOOL_CALL,
                ObservedType.CONTROL_TEXT, catalogDigest, List.of());
    }

    /** Plain prose is not a decision when the archived catalog requires an explicit answer call. */
    public static AssistantDecisionValidationFeedback nativeDecisionCallRequired(String catalogDigest)
    {
        return create(Reason.NATIVE_DECISION_CALL_REQUIRED, ActionFamily.TOOL,
                SafePath.NATIVE_CONTENT, ExpectedShape.NATIVE_TOOL_CALL,
                ObservedType.STRING, catalogDigest, List.of());
    }

    public static AssistantDecisionValidationFeedback completionReferenceNotDeclared(String trustedContractDigest)
    {
        return create(Reason.COMPLETION_REFERENCE_NOT_DECLARED, ActionFamily.COMPLETE,
                SafePath.COMPLETE_EVIDENCE_REFS, ExpectedShape.DECLARED_COMPLETION_REFERENCES,
                ObservedType.UNDECLARED_REFERENCE, trustedContractDigest, List.of());
    }

    /** Parses only the exact canonical representation emitted by {@link #canonicalJson()}. */
    public static AssistantDecisionValidationFeedback parse(String canonicalJson)
    {
        if (canonicalJson == null
                || canonicalJson.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES)
            throw new IllegalArgumentException("Invalid Assistant decision validation feedback");
        JSONObject value;
        try
        {
            value = JSON.parseObject(canonicalJson);
        }
        catch (RuntimeException invalid)
        {
            throw new IllegalArgumentException("Invalid Assistant decision validation feedback", invalid);
        }
        Set<String> fields = Set.of("schemaVersion", "reason", "actionFamily", "safePath",
                "expectedShape", "observedType", "trustedContractDigest", "issues");
        if (value == null || value.size() != fields.size() || !fields.containsAll(value.keySet()))
            throw new IllegalArgumentException("Invalid Assistant decision validation feedback fields");
        try
        {
            JSONArray rawIssues = value.getJSONArray("issues");
            if (rawIssues == null || rawIssues.size() > MAX_ISSUES)
                throw new IllegalArgumentException("Invalid Assistant decision validation issues");
            List<Issue> issues = new java.util.ArrayList<>();
            Set<String> issueFields = Set.of("code", "safeField", "fieldDigest");
            for (Object raw : rawIssues)
            {
                if (!(raw instanceof JSONObject item) || item.size() != issueFields.size()
                        || !issueFields.containsAll(item.keySet()))
                    throw new IllegalArgumentException("Invalid Assistant decision validation issue fields");
                Object safeField = item.get("safeField");
                Object fieldDigest = item.get("fieldDigest");
                if (safeField != null && !(safeField instanceof String)
                        || fieldDigest != null && !(fieldDigest instanceof String))
                    throw new IllegalArgumentException("Invalid Assistant decision validation issue values");
                issues.add(new Issue(IssueCode.valueOf(item.getString("code")),
                        (String) safeField, (String) fieldDigest));
            }
            SafePath path = java.util.Arrays.stream(SafePath.values())
                    .filter(candidate -> candidate.value().equals(value.getString("safePath")))
                    .findFirst().orElseThrow();
            AssistantDecisionValidationFeedback feedback = new AssistantDecisionValidationFeedback(
                    value.getString("schemaVersion"), Reason.valueOf(value.getString("reason")),
                    ActionFamily.valueOf(value.getString("actionFamily")), path,
                    ExpectedShape.valueOf(value.getString("expectedShape")),
                    ObservedType.valueOf(value.getString("observedType")),
                    value.getString("trustedContractDigest"), issues);
            if (!Objects.equals(canonicalJson, feedback.canonicalJson()))
                throw new IllegalArgumentException("Assistant decision validation feedback is not canonical");
            return feedback;
        }
        catch (IllegalArgumentException invalid)
        {
            throw invalid;
        }
        catch (RuntimeException invalid)
        {
            throw new IllegalArgumentException("Invalid Assistant decision validation feedback", invalid);
        }
    }

    public JSONObject toJson()
    {
        return JSON.parseObject(canonicalJson());
    }

    public String canonicalJson()
    {
        return canonicalJson(schemaVersion, reason, actionFamily, safePath, expectedShape,
                observedType, trustedContractDigest, issues);
    }

    public String digest()
    {
        return SecureUtil.sha256(canonicalJson());
    }

    private static AssistantDecisionValidationFeedback create(Reason reason, ActionFamily actionFamily,
                                                                SafePath safePath,
                                                                ExpectedShape expectedShape,
                                                                ObservedType observedType,
                                                                String trustedContractDigest,
                                                                List<Issue> issues)
    {
        return new AssistantDecisionValidationFeedback(SCHEMA_VERSION, reason, actionFamily,
                safePath, expectedShape, observedType, trustedContractDigest, issues);
    }

    private static Issue unknownField(String field)
    {
        if (field != null && field.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$")
                && SAFE_FIELDS.contains(field))
        {
            return new Issue(IssueCode.UNDECLARED_FIELD, field, null);
        }
        return new Issue(IssueCode.UNDECLARED_FIELD, null,
                SecureUtil.sha256(field == null ? "" : field));
    }

    private static String canonicalJson(String schemaVersion, Reason reason,
                                        ActionFamily actionFamily, SafePath safePath,
                                        ExpectedShape expectedShape, ObservedType observedType,
                                        String trustedContractDigest, List<Issue> issues)
    {
        JSONObject value = new JSONObject();
        value.put("schemaVersion", schemaVersion);
        value.put("reason", reason.name());
        value.put("actionFamily", actionFamily.name());
        value.put("safePath", safePath.value());
        value.put("expectedShape", expectedShape.name());
        value.put("observedType", observedType.name());
        value.put("trustedContractDigest", trustedContractDigest);
        JSONArray issueValues = new JSONArray();
        for (Issue issue : issues)
        {
            JSONObject item = new JSONObject();
            item.put("code", issue.code().name());
            item.put("safeField", issue.safeField());
            item.put("fieldDigest", issue.fieldDigest());
            issueValues.add(item);
        }
        value.put("issues", issueValues);
        return JSON.toJSONString(value, JSONWriter.Feature.MapSortField,
                JSONWriter.Feature.WriteMapNullValue);
    }

    private static void validateReason(Reason reason, ActionFamily actionFamily, SafePath safePath,
                                       ExpectedShape expectedShape, ObservedType observedType,
                                       List<Issue> issues)
    {
        boolean valid = switch (reason)
        {
            case CHAT_MESSAGE_OBJECT_REQUIRED -> actionFamily == ActionFamily.CHAT
                    && safePath == SafePath.CHAT_MESSAGE && expectedShape == ExpectedShape.OBJECT
                    && observedType.isJsonValueType() && observedType != ObservedType.OBJECT
                    && issues.isEmpty();
            case COMPLETION_REFERENCE_NOT_DECLARED -> actionFamily == ActionFamily.COMPLETE
                    && safePath == SafePath.COMPLETE_EVIDENCE_REFS
                    && expectedShape == ExpectedShape.DECLARED_COMPLETION_REFERENCES
                    && observedType == ObservedType.UNDECLARED_REFERENCE && issues.isEmpty();
            case NATIVE_DECISION_CALL_REQUIRED -> actionFamily == ActionFamily.TOOL
                    && safePath == SafePath.NATIVE_CONTENT && expectedShape == ExpectedShape.NATIVE_TOOL_CALL
                    && observedType == ObservedType.STRING && issues.isEmpty();
            case NATIVE_CONTROL_TEXT_REQUIRES_TOOL_CALL -> actionFamily == ActionFamily.TOOL
                    && safePath == SafePath.NATIVE_CONTENT && expectedShape == ExpectedShape.NATIVE_TOOL_CALL
                    && observedType == ObservedType.CONTROL_TEXT && issues.isEmpty();
            case PLAN_ARRAY_REQUIRED -> actionFamily == ActionFamily.PLAN
                    && Set.of(SafePath.PLAN_ASSUMPTIONS, SafePath.PLAN_DELIVERABLES,
                            SafePath.PLAN_COMPLETION_CRITERIA).contains(safePath)
                    && expectedShape == ExpectedShape.ARRAY
                    && observedType.isJsonValueType() && observedType != ObservedType.ARRAY
                    && issues.isEmpty();
            case PLAN_STEPS_ARRAY_REQUIRED -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_STEPS && expectedShape == ExpectedShape.ARRAY
                    && observedType.isJsonValueType() && observedType != ObservedType.ARRAY
                    && issues.isEmpty();
            case PLAN_STEP_UNKNOWN_FIELD -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_STEP
                    && expectedShape == ExpectedShape.DECLARED_FIELDS_ONLY
                    && observedType == ObservedType.OBJECT && !issues.isEmpty();
            case PLAN_INPUT_OUTPUT_NOT_DECLARED -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_INPUT_ARTIFACT_BINDING
                    && expectedShape == ExpectedShape.DECLARED_LOGICAL_OUTPUT
                    && observedType == ObservedType.UNDECLARED_REFERENCE && issues.isEmpty();
            case PLAN_DEPENDENCY_NOT_DECLARED -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_STEPS
                    && expectedShape == ExpectedShape.CURRENT_PLAN_STEP_DEPENDENCIES
                    && observedType == ObservedType.UNDECLARED_REFERENCE && issues.isEmpty();
            case PLAN_COMPLETION_EVIDENCE_UNSATISFIABLE -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_COMPLETION_CRITERIA
                    && expectedShape == ExpectedShape.PLAN_PRODUCED_EVIDENCE_OR_DIRECT_READ_ONLY_QUERY
                    && observedType == ObservedType.MISSING_PREREQUISITE && issues.isEmpty();
            case PLAN_REPEATS_COMPLETED_OPERATION -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_STEPS
                    && expectedShape == ExpectedShape.REMAINING_OPERATIONS_ONLY
                    && observedType == ObservedType.UNDECLARED_REFERENCE && issues.isEmpty();
            case PLAN_BILLABLE_OUTPUT_REQUIRED, PLAN_ARTIFACT_OUTPUT_REQUIRED -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_OUTPUT_ARTIFACT_IDS
                    && expectedShape == ExpectedShape.SINGLE_LOGICAL_ARTIFACT_ID
                    && observedType == ObservedType.MISSING_PREREQUISITE && issues.isEmpty();
            case PLAN_INPUTS_NOT_READY -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_INPUT_ARTIFACT_BINDING
                    && expectedShape == ExpectedShape.RESOLVED_PLAN_INPUTS
                    && observedType == ObservedType.UNRESOLVED_REFERENCE && issues.isEmpty();
            case PLAN_READ_PREREQUISITES_REQUIRED -> actionFamily == ActionFamily.PLAN
                    && safePath == SafePath.PLAN_STEPS
                    && expectedShape == ExpectedShape.CURRENT_PLAN_READ_DEPENDENCIES
                    && observedType == ObservedType.MISSING_PREREQUISITE && issues.isEmpty();
            case NATIVE_ARGUMENTS_OBJECT_REQUIRED -> actionFamily.supportsNativeArguments()
                    && safePath == SafePath.NATIVE_ARGUMENTS && expectedShape == ExpectedShape.OBJECT
                    && observedType.isJsonValueType() && observedType != ObservedType.OBJECT
                    && issues.isEmpty();
            case NATIVE_ARGUMENTS_JSON_INVALID -> actionFamily.supportsNativeArguments()
                    && safePath == SafePath.NATIVE_ARGUMENTS && expectedShape == ExpectedShape.OBJECT
                    && observedType == ObservedType.INVALID_JSON && issues.isEmpty();
            case ARGUMENT_SCHEMA_MISMATCH -> actionFamily == ActionFamily.TOOL
                    && safePath == SafePath.TOOL_ARGUMENTS
                    && expectedShape == ExpectedShape.TRUSTED_SCHEMA
                    && observedType == ObservedType.SCHEMA_MISMATCH && issues.isEmpty();
            case NATIVE_CONTROL_SCHEMA_MISMATCH -> actionFamily == ActionFamily.ASK
                    && safePath == SafePath.NATIVE_ARGUMENTS
                    && expectedShape == ExpectedShape.TRUSTED_SCHEMA
                    && observedType == ObservedType.SCHEMA_MISMATCH && issues.isEmpty();
            case NATIVE_FUNCTION_NAME_NOT_EXPOSED -> actionFamily == ActionFamily.TOOL
                    && safePath == SafePath.NATIVE_FUNCTION_NAME
                    && expectedShape == ExpectedShape.EXPOSED_FUNCTION_NAME
                    && observedType == ObservedType.UNDECLARED_FUNCTION_NAME && issues.isEmpty();
        };
        if (!valid) throw new IllegalArgumentException("Assistant decision validation reason mismatch");
    }

    public enum Reason
    {
        CHAT_MESSAGE_OBJECT_REQUIRED,
        COMPLETION_REFERENCE_NOT_DECLARED,
        NATIVE_DECISION_CALL_REQUIRED,
        NATIVE_CONTROL_TEXT_REQUIRES_TOOL_CALL,
        PLAN_STEPS_ARRAY_REQUIRED,
        PLAN_ARRAY_REQUIRED,
        PLAN_STEP_UNKNOWN_FIELD,
        PLAN_INPUT_OUTPUT_NOT_DECLARED,
        PLAN_DEPENDENCY_NOT_DECLARED,
        PLAN_COMPLETION_EVIDENCE_UNSATISFIABLE,
        PLAN_REPEATS_COMPLETED_OPERATION,
        PLAN_BILLABLE_OUTPUT_REQUIRED,
        PLAN_ARTIFACT_OUTPUT_REQUIRED,
        PLAN_INPUTS_NOT_READY,
        PLAN_READ_PREREQUISITES_REQUIRED,
        NATIVE_ARGUMENTS_OBJECT_REQUIRED,
        NATIVE_ARGUMENTS_JSON_INVALID,
        ARGUMENT_SCHEMA_MISMATCH,
        NATIVE_CONTROL_SCHEMA_MISMATCH,
        NATIVE_FUNCTION_NAME_NOT_EXPOSED
    }

    public enum ActionFamily
    {
        CHAT,
        PLAN,
        TOOL,
        ASK,
        CHECKPOINT,
        COMPLETE;

        private boolean supportsNativeArguments()
        {
            return true;
        }
    }

    public enum SafePath
    {
        CHAT_MESSAGE("$.chat.message"),
        COMPLETE_EVIDENCE_REFS("$.complete.completionCriteriaResults[*].evidenceRefs"),
        NATIVE_CONTENT("$.native.content"),
        PLAN_STEPS("$.plan.steps"),
        PLAN_ASSUMPTIONS("$.plan.assumptions"),
        PLAN_DELIVERABLES("$.plan.deliverables"),
        PLAN_COMPLETION_CRITERIA("$.plan.completionCriteria"),
        PLAN_STEP("$.plan.steps[*]"),
        PLAN_OUTPUT_ARTIFACT_IDS("$.plan.steps[*].outputLogicalArtifactIds"),
        PLAN_INPUT_ARTIFACT_BINDING("$.plan.steps[*].inputArtifactBindings[*]"),
        NATIVE_ARGUMENTS("$.native.arguments"),
        NATIVE_FUNCTION_NAME("$.native.name"),
        TOOL_ARGUMENTS("$.tool.arguments");

        private final String value;

        SafePath(String value) { this.value = value; }

        public String value() { return value; }
    }

    public enum ExpectedShape
    {
        DECLARED_COMPLETION_REFERENCES,
        NATIVE_TOOL_CALL,
        ARRAY,
        OBJECT,
        DECLARED_FIELDS_ONLY,
        DECLARED_LOGICAL_OUTPUT,
        SINGLE_LOGICAL_ARTIFACT_ID,
        REMAINING_OPERATIONS_ONLY,
        RESOLVED_PLAN_INPUTS,
        CURRENT_PLAN_READ_DEPENDENCIES,
        CURRENT_PLAN_STEP_DEPENDENCIES,
        PLAN_PRODUCED_EVIDENCE_OR_DIRECT_READ_ONLY_QUERY,
        TRUSTED_SCHEMA,
        EXPOSED_FUNCTION_NAME
    }

    public enum ObservedType
    {
        INVALID_JSON,
        CONTROL_TEXT,
        NULL,
        STRING,
        NUMBER,
        BOOLEAN,
        ARRAY,
        OBJECT,
        UNDECLARED_REFERENCE,
        UNRESOLVED_REFERENCE,
        MISSING_PREREQUISITE,
        SCHEMA_MISMATCH,
        UNDECLARED_FUNCTION_NAME;

        private boolean isJsonValueType()
        {
            return this == NULL || this == STRING || this == NUMBER || this == BOOLEAN
                    || this == ARRAY || this == OBJECT;
        }

        public static ObservedType fromJsonValue(Object value)
        {
            if (value == null) return NULL;
            if (value instanceof JSONObject) return OBJECT;
            if (value instanceof JSONArray) return ARRAY;
            if (value instanceof String) return STRING;
            if (value instanceof Number) return NUMBER;
            if (value instanceof Boolean) return BOOLEAN;
            throw new IllegalArgumentException("Unsupported JSON value type");
        }
    }

    public enum IssueCode { UNDECLARED_FIELD }

    public record Issue(IssueCode code, String safeField, String fieldDigest)
    {
        public Issue
        {
            if (code == null || (safeField == null) == (fieldDigest == null)
                    || safeField != null && !SAFE_FIELDS.contains(safeField)
                    || fieldDigest != null && !fieldDigest.matches("^[0-9a-f]{64}$"))
            {
                throw new IllegalArgumentException("Invalid Assistant decision validation issue");
            }
        }
    }
}
