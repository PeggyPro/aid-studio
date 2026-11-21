package com.aid.runtime.assistant;

import com.aid.runtime.domain.AidRuntimeApproval;
import java.util.Date;

/** Quote expiry governs acceptance. The separate run policy and billing authorization govern execution. */
public final class AssistantApprovalAcceptance {
    private AssistantApprovalAcceptance() { }

    public static boolean acceptedWithinQuoteWindow(AidRuntimeApproval approval, Date now) {
        return approval != null && "ACCEPTED".equals(approval.getStatus()) && now != null
                && approval.getAcceptedAt() != null && approval.getExpiresAt() != null
                && !approval.getAcceptedAt().after(now)
                && approval.getAcceptedAt().before(approval.getExpiresAt());
    }
}
