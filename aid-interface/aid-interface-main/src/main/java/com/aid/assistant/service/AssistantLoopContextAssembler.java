package com.aid.assistant.service;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.assistant.correction.AssistantDecisionCorrectionCandidate;
import com.aid.runtime.domain.AidRuntimeRun;

/** Builds the server-owned, bounded context supplied to one main-Agent iteration. */
public interface AssistantLoopContextAssembler
{
    /** Reuses the same permission-checked Tool projection for a native protocol receipt. */
    default Object nativeObservation(Long userId, AidRuntimeRun run,
            com.aid.runtime.domain.AidRuntimeRunStep step, int maxUtf8Bytes) {
        throw new UnsupportedOperationException("NATIVE_OBSERVATION_UNAVAILABLE");
    }

    /** Fresh source visibility and fixed contracts only; does not assemble message or artifact bodies. */
    default com.alibaba.fastjson2.JSONObject catalogForContractRead(Long userId, AidAssistantSession session,
            AidRuntimeRun run, AssistantLoopBinding binding) {
        throw new UnsupportedOperationException("CONTEXT_CONTRACT_LOOKUP_UNAVAILABLE");
    }

    String assemble(Long userId, AidAssistantSession session, AidRuntimeRun run,
                    AssistantLoopBinding binding, long acceptedRunRevision, int iteration);

    String assemble(Long userId, AidAssistantSession session, AidRuntimeRun run,
                    AssistantLoopBinding binding, long acceptedRunRevision, int iteration,
                    AssistantDecisionCorrectionCandidate correction);
}
