package com.aid.assistant.creative.service;

import com.aid.assistant.creative.dto.CreativeSegmentRequests;
import com.aid.assistant.creative.vo.CreativeSegmentViews;
import com.aid.runtime.tool.RuntimeToolInvocation;

public interface CreativeArtifactSegmentReadService {
    CreativeSegmentViews.ArtifactPage read(RuntimeToolInvocation invocation,
                                           CreativeSegmentRequests.ArtifactRead request);
}
