package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.media.event.MediaTextContextRestoringEvent;
import com.aid.media.provider.ReasoningContentSanitizer;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import java.util.*;
import java.util.function.Function;

/** Replays original assistant calls and their committed results, never model-written substitutes. */
@Component
public final class NativeAssistantConversation {
    public static final String SCOPE_KEY = "nativeConversationVersion";
    public static final String VERSION = "native-conversation/v1";
    private static final int MAX_STEPS = 2048;
    private static final int MAX_HISTORY_CHARS = 4_000_000;
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private NativeAssistantTurnArchive archive;
    @Resource private com.aid.assistant.service.AssistantLoopContextAssembler observations;

    public static boolean supportsBindings(String bindingsJson) {
        if (bindingsJson == null || bindingsJson.isBlank()) return false;
        JSONObject envelope = JSON.parseObject(bindingsJson);
        require(envelope != null && envelope.getJSONArray("tools") != null, "NATIVE_HISTORY_BINDINGS_INVALID");
        if (NativeAssistantBindingPolicy.V11.equals(envelope.getString("schemaVersion"))
                && NativeAssistantTurnState.VERSION.equals(envelope.get(NativeAssistantTurnState.BINDING_FIELD))
                && !envelope.getJSONArray("tools").isEmpty()) return true;
        return envelope.getJSONArray("tools").stream().anyMatch(raw -> raw instanceof JSONObject binding
                && "assistant_answer".equals(binding.getString("name")) && "CHAT".equals(binding.getString("action")));
    }

    @EventListener
    public void restore(MediaTextContextRestoringEvent event) {
        var request = event == null ? null : event.request();
        if (request == null || !"ASSISTANT_RUNTIME_STEP".equals(request.getBizTaskType())
                || request.getBizTaskId() == null) return;
        AidRuntimeRunStep current = steps.selectById(request.getBizTaskId());
        require(current != null && "MAIN_AGENT".equals(current.getStepKind()), "NATIVE_HISTORY_SCOPE_INVALID");
        AidRuntimeRun run = runs.selectById(current.getRunId());
        require(run != null && Objects.equals(run.getUserId(), request.getUserId()), "NATIVE_HISTORY_SCOPE_INVALID");
        if (!enabled(run, current)) return;
        require(archive.enrolled(run, false), "NATIVE_HISTORY_UNAVAILABLE");
        restoreMessages(request, history(run, current));
    }

    public boolean enabled(AidRuntimeRun run, AidRuntimeRunStep current) {
        require(run != null && current != null && Objects.equals(run.getId(), current.getRunId()), "NATIVE_HISTORY_SCOPE_INVALID");
        var prior = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0")
                .eq(AidRuntimeRunStep::getStepKind, "MAIN_AGENT")
                .lt(current.getId() != null, AidRuntimeRunStep::getId, current.getId())
                .orderByAsc(AidRuntimeRunStep::getId).last("LIMIT 1"));
        if (prior.isEmpty()) return true;
        var first = prior.get(0);
        var approval = first.getApprovalId() == null ? null : approvals.selectById(first.getApprovalId());
        JSONObject scope = approval == null ? null : JSON.parseObject(approval.getScopeJson());
        return scope != null && VERSION.equals(scope.getString(SCOPE_KEY));
    }

    static void restoreMessages(com.aid.media.dto.MediaTextGenerateRequest request, List<TextMessageItem> history) {
        history = NativeAssistantTurnState.attach(history, request.getAssistantNativeToolBindingsJson());
        List<TextMessageItem> actual = request.getMessages();
        require(actual != null && actual.size() == history.size() + 2
                && "system".equals(actual.get(0).getRole()) && "user".equals(actual.get(1).getRole()),
                "NATIVE_HISTORY_REQUEST_CHANGED");
        JSONObject frozenBindings = JSON.parseObject(request.getAssistantNativeToolBindingsJson());
        if (frozenBindings != null && frozenBindings.containsKey(NativeSubmittedAnswerReceipt.BINDING_FIELD)) {
            JSONObject frozenInput = JSON.parseObject(actual.get(1).getContent());
            history = NativeSubmittedAnswerReceipt.attach(history, request.getAssistantNativeToolBindingsJson(),
                    frozenInput == null ? null : frozenInput.getJSONObject("input"));
        }
        Object expected = JSON.parse(ReasoningContentSanitizer.sanitizeJson(JSON.toJSONString(history)));
        Object supplied = JSON.parse(ReasoningContentSanitizer.sanitizeJson(JSON.toJSONString(actual.subList(2, actual.size()))));
        require(Objects.equals(expected, supplied), "NATIVE_HISTORY_REQUEST_CHANGED");
        List<TextMessageItem> restored = new ArrayList<>(actual.subList(0, 2));
        restored.addAll(history);
        request.setMessages(restored);
    }

    public List<TextMessageItem> history(AidRuntimeRun run, AidRuntimeRunStep current) {
        require(run != null && current != null && Objects.equals(run.getId(), current.getRunId()), "NATIVE_HISTORY_SCOPE_INVALID");
        List<AidRuntimeRunStep> previous = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0")
                .lt(current.getId() != null, AidRuntimeRunStep::getId, current.getId()).orderByAsc(AidRuntimeRunStep::getId)
                .last("LIMIT " + (MAX_STEPS + 1)));
        require(previous.size() <= MAX_STEPS, "NATIVE_HISTORY_LIMIT");
        List<AidRuntimeRunStep> main = previous.stream().filter(s -> "MAIN_AGENT".equals(s.getStepKind())).toList();
        if (!enabled(run, current)) return List.of();
        require(archive.enrolled(run, main.isEmpty()), "NATIVE_HISTORY_UNAVAILABLE");
        List<TextMessageItem> messages = new ArrayList<>();
        Set<String> callIds = new HashSet<>();
        int characters = 0;
        for (AidRuntimeRunStep step : main) {
            // Expired/revised quotes are durable attempts but never became provider turns.
            // A missing task alone is insufficient: acceptance must explicitly prove no submission.
            boolean unsubmitted = "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())
                    && Set.of("CANCELLED", "CANCELLED_PRETASK", "FAILED_PRETASK").contains(step.getStatus());
            // RELEASED is written by the unified unsubmitted-reservation guard only after
            // checking the original ledger and no-task proof; it is not a Provider refund status.
            boolean releasedBeforeTask = "RELEASED".equals(step.getProviderAcceptanceState())
                    && Set.of("CANCELLED_PRETASK", "FAILED_PRETASK").contains(step.getStatus());
            if (step.getMediaTaskId() == null && (unsubmitted || releasedBeforeTask)) continue;
            if (isRejectedRecoveryTurn(step, previous)) continue;
            require("SUCCEEDED".equals(step.getStatus()) && step.getMediaTaskId() != null,
                    "NATIVE_HISTORY_TURN_UNRESOLVED");
            TextMessageItem assistant = archive.read(run, step.getId(), step.getMediaTaskId());
            List<TextMessageItem> exchange = exchange(assistant, step, previous,
                    executed -> observations.nativeObservation(run.getUserId(), run, executed, 2_000_000),
                    approvals::selectById);
            for (var call : Optional.ofNullable(assistant.getToolCalls()).orElse(List.of()))
                require(callIds.add(call.getId()), "NATIVE_HISTORY_CALL_DUPLICATE");
            characters += JSON.toJSONString(exchange).length();
            require(characters <= MAX_HISTORY_CHARS, "NATIVE_HISTORY_LIMIT");
            messages.addAll(exchange);
        }
        return List.copyOf(messages);
    }

    /** A rejected, unexecuted decision is audit evidence, not an executable history exchange. */
    static boolean isRejectedRecoveryTurn(AidRuntimeRunStep main, List<AidRuntimeRunStep> committed) {
        if (!"FAILED".equals(main.getStatus()) || main.getMediaTaskId() == null
                || !AssistantModelRecovery.isRejectedRecoveryStep(main.getErrorMessage())
                || main.getOutputJson() != null && !main.getOutputJson().isBlank()) return false;
        Long next = committed.stream().filter(s -> "MAIN_AGENT".equals(s.getStepKind())
                && s.getId() > main.getId()).map(AidRuntimeRunStep::getId).min(Long::compareTo).orElse(null);
        require(committed.stream().noneMatch(s -> !"MAIN_AGENT".equals(s.getStepKind())
                && s.getId() > main.getId() && (next == null || s.getId() < next)),
                "NATIVE_HISTORY_RECOVERY_HAS_OPERATION");
        return true;
    }

    static List<TextMessageItem> exchange(TextMessageItem assistant, AidRuntimeRunStep main,
                                           List<AidRuntimeRunStep> committed) {
        return exchange(assistant, main, committed, executed -> executed.getOutputJson() == null ? null
                : com.aid.assistant.service.AssistantObservationSanitizer.project(JSON.parse(executed.getOutputJson()), 0),
                ignored -> null);
    }

    private static List<TextMessageItem> exchange(TextMessageItem assistant, AidRuntimeRunStep main,
            List<AidRuntimeRunStep> committed, Function<AidRuntimeRunStep, Object> observation,
            Function<Long, AidRuntimeApproval> approvalLookup) {
        require(assistant != null && "assistant".equals(assistant.getRole()) && main != null
                && main.getId() != null && main.getId() > 0
                && "SUCCEEDED".equals(main.getStatus()), "NATIVE_HISTORY_TURN_INVALID");
        Long nextMainId = committed.stream().filter(step -> "MAIN_AGENT".equals(step.getStepKind())
                        && Objects.equals(step.getRunId(), main.getRunId()) && step.getId() != null
                        && step.getId() > main.getId()).map(AidRuntimeRunStep::getId).min(Long::compareTo).orElse(null);
        JSONObject decision = JSON.parseObject(main.getOutputJson());
        require(decision != null, "NATIVE_HISTORY_DECISION_MISSING");
        var calls = assistant.getToolCalls();
        if (calls == null || calls.isEmpty()) return List.of(assistant);
        List<JSONObject> decisions = new ArrayList<>();
        if (NativeAssistantToolBatch.ACTION.equals(decision.getString("action"))) {
            var batch = NativeAssistantToolBatch.parse(main.getOutputJson());
            require(batch.calls().size() == calls.size(), "NATIVE_HISTORY_BATCH_CHANGED");
            for (int i = 0; i < calls.size(); i++) {
                var saved = batch.calls().get(i);
                require(Objects.equals(saved.callId(), calls.get(i).getId())
                        && Objects.equals(saved.bindingName(), calls.get(i).getName()), "NATIVE_HISTORY_CALL_CHANGED");
                decisions.add(JSON.parseObject(saved.decisionJson()));
            }
        } else {
            require(calls.size() == 1, "NATIVE_HISTORY_CALL_CHANGED");
            decisions.add(decision);
        }
        List<TextMessageItem> result = new ArrayList<>();
        result.add(assistant);
        Long stoppedAfterStep = null;
        for (int i = 0; i < calls.size(); i++) {
            JSONObject applied = decisions.get(i);
            String action = applied.getString("action");
            JSONObject receipt;
            boolean failed = false;
            if (Set.of("TOOL", "SKILL").contains(action)) {
                JSONObject operation = applied.getJSONObject(action.toLowerCase(Locale.ROOT));
                require(operation != null, "NATIVE_HISTORY_OPERATION_MISSING");
                List<AidRuntimeRunStep> matches = committed.stream()
                        .filter(s -> !"MAIN_AGENT".equals(s.getStepKind())
                                && Objects.equals(s.getRunId(), main.getRunId())
                                // An unstarted batch call may be requested again in a later turn
                                // with the same discovery key/attempt. Its later receipt is not
                                // evidence that the earlier skipped call executed.
                                && s.getId() != null && s.getId() > main.getId()
                                && (nextMainId == null || s.getId() < nextMainId)
                                && Objects.equals(s.getStableStepId(), operation.getString("stableStepId"))
                                && Objects.equals(s.getPlanRevision(), applied.getLong("observedPlanRevision"))
                                && Objects.equals(s.getAttemptNo(), operation.getInteger("attemptNo")))
                        .toList();
                if (stoppedAfterStep != null) {
                    // The ordered batch executor returns immediately after a non-successful
                    // member. No execution row exists for the unstarted suffix. Preserve every
                    // original call ID, but do not fabricate a task, executed result or success.
                    require(NativeAssistantToolBatch.ACTION.equals(decision.getString("action"))
                                    && matches.isEmpty(), "NATIVE_HISTORY_BATCH_CONTINUED_AFTER_FAILURE");
                    TextMessageItem unstarted = new TextMessageItem();
                    unstarted.setRole("tool");
                    unstarted.setToolCallId(calls.get(i).getId());
                    unstarted.setToolError(true);
                    unstarted.setContent(AssistantMainInputContract.serialize(JSONObject.of(
                            "status", "NOT_EXECUTED", "providerAcceptanceState", "NOT_SUBMITTED",
                            "reason", "BATCH_STOPPED_AFTER_FAILURE", "failedRuntimeStepId", stoppedAfterStep)));
                    result.add(unstarted);
                    continue;
                }
                require(matches.size() == 1, "NATIVE_HISTORY_RECEIPT_MISSING");
                AidRuntimeRunStep execution = matches.get(0);
                AidRuntimeApproval approval = execution.getApprovalId() != null
                        && "TOOL".equals(execution.getStepKind())
                        && Set.of("FAILED_PRETASK", "CANCELLED_PRETASK").contains(execution.getStatus())
                        ? approvalLookup.apply(execution.getApprovalId()) : null;
                boolean preTaskFailure = com.aid.runtime.assistant.execution.AssistantPreTaskHistoryReceipt
                        .verified(main, execution, operation, approval);
                require(Set.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED").contains(execution.getStatus())
                                || preTaskFailure,
                        "NATIVE_HISTORY_RECEIPT_UNRESOLVED");
                require(!"TOOL".equals(action) || Objects.equals(execution.getToolCode(), operation.getString("toolCode")),
                        "NATIVE_HISTORY_BINDING_CHANGED");
                receipt = JSONObject.of("runtimeStepId", execution.getId(), "status", execution.getStatus(),
                        "output", preTaskFailure ? null : observation.apply(execution),
                        "outputArtifactRef", execution.getOutputArtifactRef());
                failed = !"SUCCEEDED".equals(execution.getStatus());
                if (failed && NativeAssistantToolBatch.ACTION.equals(decision.getString("action"))) {
                    require(execution.getId() != null && execution.getId() > 0,
                            "NATIVE_HISTORY_RECEIPT_MISSING");
                    stoppedAfterStep = execution.getId();
                }
                if (preTaskFailure) receipt.put("providerAcceptanceState", execution.getProviderAcceptanceState());
                if (failed) receipt.put("error", com.aid.assistant.service.AssistantObservationSanitizer.project(execution.getErrorMessage(), 0));
            } else {
                require(Set.of("PLAN", "CHAT", "ASK", "CHECKPOINT", "COMPLETE", "FAIL").contains(action),
                        "NATIVE_HISTORY_ACTION_INVALID");
                // The coordinator persisted this decision before scheduling the next MAIN step.
                // This acknowledges the control operation, not the success of any future generation.
                receipt = JSONObject.of("decisionId", applied.getString("decisionId"), "action", action,
                        "status", "APPLIED", "result", applied.get(action.toLowerCase(Locale.ROOT)));
            }
            TextMessageItem tool = new TextMessageItem();
            tool.setRole("tool");
            tool.setToolCallId(calls.get(i).getId());
            tool.setContent(AssistantMainInputContract.serialize(receipt));
            if (failed) tool.setToolError(true);
            result.add(tool);
        }
        return result;
    }

    private static void require(boolean ok, String code) { if (!ok) throw new ServiceException(code); }
}
