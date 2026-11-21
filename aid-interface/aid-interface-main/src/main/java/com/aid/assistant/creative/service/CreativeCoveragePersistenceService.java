package com.aid.assistant.creative.service;

import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;

/** Prepares and atomically applies one source coverage CAS operation. */
public interface CreativeCoveragePersistenceService {
    PreparedCoverage prepare(RuntimeToolInvocation invocation, CreativeAuditRequests.CoverageMark request);

    CreativeViews.CoverageReceipt persist(RuntimeToolPreparedInvocation invocation,
                                          CreativeAuditRequests.PreparedCoverageMark prepared,
                                          RuntimeOperationSubmissionClaim claim);

    record PreparedCoverage(CreativeAuditRequests.PreparedCoverageMark payload,
                            AssistantRequests.ContextRef targetRef) {
    }

    /** A claimed replay has no matching durable coverage receipt and must remain UNKNOWN. */
    final class ReceiptMissingException extends RuntimeException {
        public ReceiptMissingException() {
            super("Creative coverage receipt is unavailable for reconciliation");
        }
    }
}
