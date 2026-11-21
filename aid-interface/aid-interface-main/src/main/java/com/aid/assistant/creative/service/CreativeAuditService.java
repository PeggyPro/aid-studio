package com.aid.assistant.creative.service;

import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.runtime.assistant.RuntimeLease;

public interface CreativeAuditService {
    Long markCoverage(CreativeAuditRequests.CoverageMark request,
                      RuntimeLease runLease, RuntimeLease stepLease);

    Long recordReview(CreativeAuditRequests.ReviewRecord request,
                      RuntimeLease runLease, RuntimeLease stepLease);
}
