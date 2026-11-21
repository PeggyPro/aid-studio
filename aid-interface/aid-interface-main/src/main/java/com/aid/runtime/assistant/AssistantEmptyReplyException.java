package com.aid.runtime.assistant;

/** A completed answer did not contain any visible reply. */
final class AssistantEmptyReplyException extends IllegalArgumentException {
    static final String CODE = "MODEL_OUTPUT_EMPTY";

    AssistantEmptyReplyException() {
        super(CODE);
    }
}
