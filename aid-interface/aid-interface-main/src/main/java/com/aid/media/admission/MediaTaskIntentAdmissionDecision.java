package com.aid.media.admission;

/** Result of atomically binding the first durable media task to its owning business step. */
public enum MediaTaskIntentAdmissionDecision
{
    PERSIST_NEW,
    REPLAY_EXISTING,
    RECONCILE_ONLY
}
