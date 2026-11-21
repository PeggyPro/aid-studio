package com.aid.media.admission;

/** The durable task-intent outcome is ambiguous or belongs to another fenced worker. */
public class MediaTaskIntentConflictException extends RuntimeException
{
    public MediaTaskIntentConflictException()
    {
        super("MEDIA_TASK_INTENT_RECONCILIATION_REQUIRED");
    }
}
