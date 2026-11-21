package com.aid.media.reconciliation;

/** Optional diagnostic projection; must not submit, settle, release permits or change task outcomes. */
public interface MediaSubmissionAnomalyObserver {
    void refreshSubmissionAnomalies(int maximumRows);
}
