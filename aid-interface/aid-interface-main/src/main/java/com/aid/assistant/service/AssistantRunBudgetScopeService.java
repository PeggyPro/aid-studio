package com.aid.assistant.service;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.vo.AssistantExecutionViews;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.domain.AidRuntimeRun;

public interface AssistantRunBudgetScopeService
{
    AssistantExecutionViews.RunBudgetScope discover(Long userId, AidAssistantSession session,
                                                     AidRuntimeRun run, AssistantLoopBinding binding);

    AssistantExecutionViews.RunBudgetScope discover(Long userId, AidAssistantSession session,
                                                     AidRuntimeRun run);
}
