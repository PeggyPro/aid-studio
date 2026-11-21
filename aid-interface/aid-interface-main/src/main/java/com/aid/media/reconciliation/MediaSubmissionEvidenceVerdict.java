package com.aid.media.reconciliation;

/** What durable, protocol-authoritative evidence proves about one provider submission. */
public enum MediaSubmissionEvidenceVerdict {
    ACCEPTED,
    TERMINAL,
    DEFINITIVELY_NOT_ACCEPTED,
    UNKNOWN,
    UNSUPPORTED
}
