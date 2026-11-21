package com.aid.media.event;

/** A recognized upstream state clears availability diagnostics, including when still queued. */
public record MediaTaskUpstreamReachableEvent(Long taskId, String taskStatus) { }
