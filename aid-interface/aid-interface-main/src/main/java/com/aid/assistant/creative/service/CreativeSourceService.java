package com.aid.assistant.creative.service;

import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;

public interface CreativeSourceService {
    CreativeViews.SourceRevision ingest(Long userId, CreativeRequests.SourceIngest request,
                                        String contextManifestJson,
                                        RuntimeOperationSubmissionClaim submissionClaim);

    CreativeViews.SourceChunkPage read(Long userId, CreativeRequests.SourceChunkRead request,
                                       String contextManifestJson);
}
