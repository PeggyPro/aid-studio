package com.aid.assistant.story.service;

import java.util.Objects;

/** A correction appends a report for the same body; it cannot reopen a reviewed body revision. */
public final class StoryReviewCorrectionPolicy {
    private StoryReviewCorrectionPolicy() { }

    public static void validate(String currentBodyRef, String reviewedBodyRef, Long followupRevision,
                                String currentReviewRef, String currentReviewHash,
                                String expectedReviewRef, String expectedReviewHash, String reason) {
        if (currentBodyRef == null || currentBodyRef.isBlank()
                || !Objects.equals(currentBodyRef, reviewedBodyRef) || followupRevision != null
                || currentReviewRef == null || currentReviewRef.isBlank()
                || !Objects.equals(currentReviewRef, expectedReviewRef)
                || currentReviewHash == null || !currentReviewHash.matches("[0-9a-f]{64}")
                || !Objects.equals(currentReviewHash, expectedReviewHash)
                || reason == null || reason.isBlank() || reason.length() > 2000) {
            throw new InvalidCorrectionException();
        }
    }

    public static final class InvalidCorrectionException extends IllegalArgumentException {
        public InvalidCorrectionException() { super("Invalid screenplay review correction identity"); }
    }
}
