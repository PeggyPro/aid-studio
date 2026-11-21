package com.aid.media.event;

/** Signals free capacity while the task that released it remains queued. */
public record MediaQueueSlotReleasedEvent(Long taskId) { }
