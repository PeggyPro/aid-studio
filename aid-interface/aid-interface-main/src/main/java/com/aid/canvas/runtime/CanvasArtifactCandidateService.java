package com.aid.canvas.runtime;

import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;

import java.util.List;

/** Candidate media results and explicit canvas-node adoption for assistant runs. */
public interface CanvasArtifactCandidateService {
    List<Candidate> list(RuntimeScopeAccess.Scope scope);

    /** Return a committed adoption receipt for the same logical invocation, or null when none exists. */
    Adoption replay(RuntimeScopeAccess.Scope scope, Long resultId, String invocationId);

    Adoption adopt(RuntimeScopeAccess.Scope scope, Long resultId, String invocationId,
                   RuntimeOperationSubmissionClaim submissionClaim);

    record Candidate(String artifactRef, Long resultId, Long taskId, String mediaType,
                     String mimeType, Long resourceId, Long inputObjectVersion) { }

    record Adoption(String artifactRef, Long resultId, Long taskId, String objectId,
                    Long previousObjectVersion, Long objectVersion, Long selectionVersion,
                    Long workspaceRevision, Long eventSeq, String operationId) { }
}
