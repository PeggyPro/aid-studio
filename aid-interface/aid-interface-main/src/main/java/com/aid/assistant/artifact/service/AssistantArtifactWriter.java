package com.aid.assistant.artifact.service;

import com.aid.assistant.artifact.dto.ArtifactWriteRequests;
import com.aid.assistant.artifact.vo.ArtifactRef;
import com.aid.runtime.assistant.RuntimeLease;

/** Internal candidate writer. Every mutation is fenced by both current run and step leases. */
public interface AssistantArtifactWriter {
    ArtifactRef createCandidate(ArtifactWriteRequests.CreateCandidate request,
                                RuntimeLease runLease, RuntimeLease stepLease);

    ArtifactRef appendChunk(ArtifactWriteRequests.AppendChunk request,
                            RuntimeLease runLease, RuntimeLease stepLease);

    ArtifactRef finalizeCandidate(ArtifactWriteRequests.FinalizeCandidate request,
                                  RuntimeLease runLease, RuntimeLease stepLease);

    ArtifactRef finalizeMediaCandidate(ArtifactWriteRequests.FinalizeMediaCandidate request,
                                       RuntimeLease runLease, RuntimeLease stepLease);
}
