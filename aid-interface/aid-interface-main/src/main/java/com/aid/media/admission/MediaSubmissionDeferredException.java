package com.aid.media.admission;

/** Internal scheduling signal: the task is durable, but Provider submission is paused. */
public class MediaSubmissionDeferredException extends RuntimeException {
    public MediaSubmissionDeferredException() {
        super("MEDIA_SUBMISSION_DEFERRED_UNTIL_RESUME");
    }
}
