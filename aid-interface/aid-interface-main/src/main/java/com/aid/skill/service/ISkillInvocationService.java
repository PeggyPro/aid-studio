package com.aid.skill.service;

import com.aid.skill.dto.SkillInvocationRequests;
import com.aid.skill.vo.SkillInvocationVO;

import java.util.List;

/** 所有 Web/Open API/CLI 适配器共用的唯一 Skill 调用服务。 */
public interface ISkillInvocationService {
    String LEGACY_AUTHORITY_PUBLISHED_AGENT = "PUBLISHED_AGENT";
    String LEGACY_AUTHORITY_DRAFT_TRIAL = "DRAFT_TRIAL";

    SkillInvocationVO invoke(SkillInvocationRequests.InvokeRequest request, Long userId,
                             String operator, String invokeSource);

    /** Trusted Assistant fixed-version entrypoint; envelope, claim, version and digest must all match. */
    SkillInvocationVO invokeFixedVersion(SkillInvocationRequests.InvokeRequest request, Long expectedVersionId,
                                         String expectedPackageDigest, Long userId,
                                         String operator, String invokeSource);

    /**
     * Trusted server-only compatibility entrypoint for persisted LEGACY_PLAN Skill orchestrators.
     * The implementation must re-derive authority from the stored Agent/ref or draft/test grant;
     * callers cannot turn this into an Assistant admission claim.
     */
    LegacyFixedVersionResult invokeLegacyFixedVersion(SkillInvocationRequests.InvokeRequest request,
                                                       Long expectedVersionId, String expectedPackageDigest,
                                                       LegacyFixedVersionAuthority authority, Long userId,
                                                       String operator);

    /**
     * A trusted legacy invocation either returns its complete view or a stable accepted Run identity
     * whose projection/binding must be recovered by replaying the same request.
     */
    record LegacyFixedVersionResult(Long runId, SkillInvocationVO view, boolean recoveryRequired) {
        public static LegacyFixedVersionResult complete(SkillInvocationVO view) {
            if (view == null || view.getRunId() == null) {
                throw new IllegalArgumentException("Legacy Skill complete result requires a Run");
            }
            return new LegacyFixedVersionResult(view.getRunId(), view, false);
        }

        public static LegacyFixedVersionResult recovery(Long runId) {
            if (runId == null || runId <= 0) {
                throw new IllegalArgumentException("Legacy Skill recovery result requires a Run ID");
            }
            return new LegacyFixedVersionResult(runId, null, true);
        }
    }

    record LegacyFixedVersionAuthority(String mode, Long agentId, Long agentVersionId,
                                       Long agentDraftId, String agentDraftDigest,
                                       Long testGrantId, Long adminUserId,
                                       String clientRequestId) {
        public static LegacyFixedVersionAuthority publishedAgent(Long agentId, Long agentVersionId) {
            return new LegacyFixedVersionAuthority(LEGACY_AUTHORITY_PUBLISHED_AGENT,
                    agentId, agentVersionId, null, null, null, null, null);
        }

        public static LegacyFixedVersionAuthority draftTrial(Long agentId, Long agentDraftId,
                                                              String agentDraftDigest, Long testGrantId,
                                                              Long adminUserId, String clientRequestId) {
            return new LegacyFixedVersionAuthority(LEGACY_AUTHORITY_DRAFT_TRIAL,
                    agentId, null, agentDraftId, agentDraftDigest,
                    testGrantId, adminUserId, clientRequestId);
        }
    }

    /** Find an existing Assistant-owned invocation without creating or resuming it. */
    SkillInvocationVO findByAssistantIdentity(Long assistantRuntimeRunId, Long assistantParentStepId,
                                              String assistantOuterStableStepKey, Integer assistantAttemptNo,
                                              Long userId);

    SkillInvocationVO respond(SkillInvocationRequests.RespondRequest request, Long userId, String operator);

    SkillInvocationVO getRun(Long runId, Long userId);

    SkillInvocationVO.HistoryPage listHistory(SkillInvocationRequests.HistoryRequest request, Long userId);

    List<SkillInvocationVO.EventView> listEvents(SkillInvocationRequests.EventPageRequest request, Long userId);

    void cancel(Long runId, Long userId, String operator);

    void reconcileMediaTask(Long mediaTaskId);

    void reconcileStaleRuns();
}
