package com.aid.media.event;

import com.aid.media.dto.MediaTextGenerateRequest;

/** Synchronous trusted extension point before text validation, pricing and provider submission. */
public record MediaTextContextRestoringEvent(MediaTextGenerateRequest request) {
    @Override public String toString() { return "MediaTextContextRestoringEvent[body=redacted]"; }
}
