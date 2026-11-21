package com.aid.assistant.service;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.dto.AssistantExecutionRequests;
import com.aid.assistant.vo.AssistantExecutionViews;
import com.aid.assistant.vo.AssistantViews;
import com.aid.assistant.vo.AssistantTargetViews;

/** Durable assistant core operations. */
public interface AssistantCoreService
{
    /** Re-authorize the persisted session scope; callers requesting a lock must own a transaction. */
    com.aid.assistant.domain.AidAssistantSession authorizeSession(Long userId, Long sessionId, boolean lock);

    /** Internal recovery: apply an accepted correction only after durable cancellation/reconciliation. */
    boolean completeDeferredReplan(Long runId, com.aid.runtime.assistant.RuntimeLease lease);

    AssistantViews.Capabilities capabilities(Long userId, AssistantRequests.Capabilities request);

    AssistantViews.Session openSession(Long userId, AssistantRequests.SessionOpen request);

    AssistantViews.Page<AssistantViews.Session> pageSessions(Long userId, AssistantRequests.SessionPage request);

    AssistantViews.SessionDetail sessionDetail(Long userId, AssistantRequests.SessionDetail request);

    AssistantViews.MessageSent sendMessage(Long userId, AssistantRequests.MessageSend request);

    AssistantViews.ContextRefreshed refreshContext(Long userId, AssistantRequests.ContextRefresh request);

    AssistantExecutionViews.PlanConfirmed confirmPlan(Long userId, AssistantExecutionRequests.PlanConfirm request);

    AssistantViews.Draft saveDraft(Long userId, AssistantRequests.DraftSave request);

    AssistantViews.InputView saveView(Long userId, AssistantRequests.ViewSave request);

    AssistantViews.InputSubmitted submitInput(Long userId, AssistantRequests.InputSubmit request);

    AssistantViews.Run suspendInput(Long userId, AssistantRequests.InputSuspend request);

    AssistantViews.Run pauseRun(Long userId, AssistantRequests.RunControl request);

    AssistantViews.Run resumeRun(Long userId, AssistantRequests.RunControl request);

    AssistantViews.Run stopRun(Long userId, AssistantRequests.RunControl request);

    AssistantViews.Run runDetail(Long userId, AssistantRequests.RunDetail request);

    AssistantViews.RunStepPage pageRunSteps(Long userId, AssistantRequests.RunStepPage request);

    AssistantTargetViews.EffectResolved resolveEffect(Long userId, AssistantRequests.EffectResolve request);

    AssistantViews.EventPage pageEvents(Long userId, AssistantRequests.EventPage request);

    AssistantViews.InputRequest requestInput(Long userId, AssistantRequests.InputRequestCreate request);
}
