package com.aid.runtime.assistant;

import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/** A stable operation identity is executed once; later stages contain remaining work only. */
@Component
@RequiredArgsConstructor
public class AssistantRemainingPlanPolicy
{
    private final AidRuntimeRunStepMapper steps;
    private final RuntimeToolRegistry tools;

    public boolean repeatsCompletedOperation(Long runId, Long observedPlanRevision, JSONObject plan)
    {
        if (runId == null || observedPlanRevision == null || observedPlanRevision < 1) return false;
        Set<String> ids = operationIds(plan, tools::isUnbilledRead);
        if (ids.isEmpty()) return false;
        // One bounded metadata query, never load input/output bodies or scan other conversations.
        return !steps.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getId)
                .eq(AidRuntimeRunStep::getRunId, runId)
                .le(AidRuntimeRunStep::getPlanRevision, observedPlanRevision)
                .in(AidRuntimeRunStep::getStableStepId, ids)
                .in(AidRuntimeRunStep::getStepKind, "TOOL", "SKILL")
                .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED")
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .last("LIMIT 1")).isEmpty();
    }

    static Set<String> operationIds(JSONObject plan, Predicate<String> unbilledRead)
    {
        Set<String> ids = new LinkedHashSet<>();
        JSONArray proposed = plan == null ? null : plan.getJSONArray("steps");
        if (proposed == null || proposed.size() > 100) throw new IllegalArgumentException("Invalid plan steps");
        for (Object item : proposed)
        {
            if (!(item instanceof JSONObject step)) throw new IllegalArgumentException("Invalid plan step");
            String kind = step.getString("executorKind");
            if ("TOOL".equals(kind) && unbilledRead.test(step.getString("bindingCode"))) continue;
            String id = step.getString("stableStepId");
            if (id == null || id.isBlank() || id.length() > 64) throw new IllegalArgumentException("Invalid step identity");
            ids.add(id);
        }
        return ids;
    }

    public static String instruction()
    {
        return "后续或恢复计划仅列尚未完成的操作；不得把历史成功的生成、写入或Skill步骤复制到新计划。"
                + "同一stableStepId表示同一个逻辑操作，不得通过更名重做已完成操作。"
                + "已生成结果应从真实回执取得引用，按需读取、选用或交付；不要为满足新计划的归档判据再次生成。"
                + "新计划的完成判据仅描述本阶段能形成的证据，历史成功事实作为上下文保留。"
                + "正常只读刷新和已确认失败后的合法重试不受此限制；UNKNOWN仍必须核实原任务。";
    }
}
