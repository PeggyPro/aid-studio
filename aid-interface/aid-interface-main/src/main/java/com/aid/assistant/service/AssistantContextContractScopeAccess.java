package com.aid.assistant.service;

import com.aid.common.exception.ServiceException;
import com.aid.context.ContextContractReader;
import com.aid.context.ConversationContextMemoryStore;
import com.aid.runtime.assistant.AssistantModuleAgentResolver;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Rechecks the current session and the run's immutable Agent before exposing cached contracts. */
@Service
public class AssistantContextContractScopeAccess implements ContextContractReader.ScopeAccess {
    public static final String SCOPE = "ASSISTANT_RUN";
    private final AidRuntimeRunMapper runs;
    private final ObjectProvider<AssistantCoreService> core;
    private final ObjectProvider<AssistantModuleAgentResolver> modules;
    private final ObjectProvider<AssistantLoopContextAssembler> assembler;

    public AssistantContextContractScopeAccess(AidRuntimeRunMapper runs, ObjectProvider<AssistantCoreService> core,
            ObjectProvider<AssistantModuleAgentResolver> modules, ObjectProvider<AssistantLoopContextAssembler> assembler) {
        this.runs = Objects.requireNonNull(runs);
        this.core = Objects.requireNonNull(core);
        this.modules = Objects.requireNonNull(modules);
        this.assembler = Objects.requireNonNull(assembler);
    }

    @Override public String scopeType() { return SCOPE; }

    @Override public JSONObject authorize(ConversationContextMemoryStore.Key scope) {
        if (scope == null || !SCOPE.equals(scope.scopeType())
                || !scope.scopeKey().matches("[1-9][0-9]{0,18}")) throw invalid();
        long runId;
        try { runId = Long.parseLong(scope.scopeKey()); }
        catch (NumberFormatException overflow) { throw invalid(); }
        AidRuntimeRun run = runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, runId).eq(AidRuntimeRun::getUserId, scope.userId())
                .eq(AidRuntimeRun::getDelFlag, "0").last("LIMIT 1"));
        if (run == null || !Objects.equals(run.getId(), runId)
                || !scope.userId().equals(run.getUserId()) || !"0".equals(run.getDelFlag())
                || run.getSessionId() == null) throw invalid();
        var session = core.getObject().authorizeSession(scope.userId(), run.getSessionId(), false);
        if (session == null || !scope.userId().equals(session.getUserId())
                || !run.getSessionId().equals(session.getId()) || !"0".equals(session.getDelFlag())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId())) throw invalid();
        var binding = modules.getObject().requirePinnedForPlanningPreparation(session.getModuleCode(),
                run.getVersionId(), run.getVersionDigest());
        if (binding == null || !Objects.equals(run.getVersionId(), binding.getAgentVersionId())
                || !Objects.equals(run.getVersionDigest(), binding.getPackageDigest())) throw invalid();
        return assembler.getObject().catalogForContractRead(scope.userId(), session, run, binding);
    }

    private static ServiceException invalid() {
        return new ServiceException("CONTEXT_CONTRACT_SCOPE_INVALID");
    }
}
