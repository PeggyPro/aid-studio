package com.aid.assistant.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.context.ParentToolObservationContract;
import com.aid.runtime.assistant.execution.AssistantToolObservationScopeVerifier;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;

/** Freezes host-verified read results before child approval, independent of Skill identity. */
@Service
public class AssistantParentObservationContext {
    @Resource private AidRuntimeRunStepMapper steps;
    @Resource private RuntimeToolRegistry tools;
    @Resource private AssistantToolObservationScopeVerifier scopes;
    @Autowired(required = false) private List<AssistantToolObservationProjector> projectors = List.of();

    public JSONObject freeze(AidRuntimeRun run) {
        return freeze(run, false);
    }

    public JSONObject freeze(AidRuntimeRun run, boolean hostNodeInputs) {
        return freeze(run, hostNodeInputs, false);
    }

    public JSONObject freeze(AidRuntimeRun run, boolean hostNodeInputs, boolean originalNodeInputs) {
        if (run == null || run.getId() == null || run.getUserId() == null || run.getPlanRevision() == null
                || !"0".equals(run.getDelFlag())) throw invalid();
        var rows = steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .le(AidRuntimeRunStep::getPlanRevision, run.getPlanRevision())
                .eq(AidRuntimeRunStep::getStepKind, "TOOL")
                .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED")
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByAsc(AidRuntimeRunStep::getId).last("LIMIT 201"));
        if (rows.size() > 200) throw new ServiceException("ASSISTANT_PARENT_OBSERVATIONS_TOO_LARGE");
        JSONArray observations = new JSONArray();
        for (AidRuntimeRunStep step : rows) {
            if (!Objects.equals(step.getRunId(), run.getId())
                    || step.getPlanRevision() == null || step.getPlanRevision() < 1
                    || step.getPlanRevision() > run.getPlanRevision()
                    || !"TOOL".equals(step.getStepKind()) || !"SUCCEEDED".equals(step.getStatus())
                    || !"0".equals(step.getDelFlag()) || step.getToolCode() == null) throw invalid();
            var descriptor = tools.require(step.getToolCode());
            if (!"READ".equals(descriptor.sideEffectLevel()) || descriptor.billable()) continue;
            var scope = scopes.requireReadHistory(run.getUserId(), run, step, descriptor);
            if (scope == null) continue;
            if (!Objects.equals(descriptor.contractVersion(), scope.contractVersion())
                    || !Objects.equals(descriptor.schemaDigest(), scope.schemaDigest())
                    || step.getOutputJson() == null || step.getOutputJson().isBlank()) throw invalid();
            var typed = projectors.stream().filter(p -> p.supports(scope.toolCode())).toList();
            if (typed.size() > 1) throw invalid();
            Object output = typed.isEmpty()
                    ? AssistantObservationSanitizer.project(JSON.parse(step.getOutputJson()), 0)
                    : typed.get(0).project(new AssistantToolObservationProjector.Input(run.getUserId(),
                        run.getId(), step.getId(), step.getStableStepId(), scope.toolCode(),
                        scope.contractVersion(), scope.schemaDigest(), scope.argumentsJson(),
                        step.getOutputJson(), ParentToolObservationContract.MAX_BYTES));
            if (!(output instanceof JSONObject || output instanceof JSONArray)) throw invalid();
            if (originalNodeInputs) output = com.aid.runtime.assistant.NativeHostNodeInput.withOriginalInputs(
                    scope.toolCode(), step.getOutputJson(), output);
            else if (hostNodeInputs) output = com.aid.runtime.assistant.NativeHostNodeInput.withSavedModel(
                    scope.toolCode(), step.getOutputJson(), output);
            JSONObject item = new JSONObject();
            item.put("stepId", String.valueOf(step.getId()));
            item.put("toolCode", scope.toolCode());
            item.put("contractVersion", scope.contractVersion());
            item.put("schemaDigest", scope.schemaDigest());
            item.put("argumentsDigest", scope.argumentsDigest());
            item.put("outputDigest", SecureUtil.sha256(step.getOutputJson()));
            item.put("observation", output);
            observations.add(item);
        }
        if (observations.isEmpty()) return null;
        JSONObject result = new JSONObject();
        result.put("schemaVersion", ParentToolObservationContract.VERSION);
        result.put("sourceRunId", String.valueOf(run.getId()));
        result.put("planRevision", run.getPlanRevision());
        result.put("observations", observations);
        try { ParentToolObservationContract.require(result); }
        catch (IllegalArgumentException rejected) { throw invalid(); }
        return result;
    }

    private ServiceException invalid() {
        return new ServiceException("ASSISTANT_PARENT_OBSERVATIONS_INVALID");
    }
}
