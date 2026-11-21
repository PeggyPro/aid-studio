package com.aid.media.model;

/** Local terminal policy; never presented as an upstream generation result. */
public enum MediaTaskFailureReason {
    SYNCHRONOUS_INTERRUPTED,
    SUBMISSION_FAILED,
    UPSTREAM_QUERY_UNAVAILABLE
}
