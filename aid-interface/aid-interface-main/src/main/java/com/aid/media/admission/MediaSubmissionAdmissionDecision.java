package com.aid.media.admission;

/** Provider出站前的持久化认领结果。 */
public enum MediaSubmissionAdmissionDecision {
    /** 本次事务首次完成业务状态与预算认领，允许一次Provider出站。 */
    PROCEED_NEW_CLAIM,
    /** A persisted task may be retried after the owning runtime resumes; no Provider call occurred. */
    DEFER_UNTIL_RESUME,
    /** 之前已认领或结果未知，只允许进入对账，禁止重复Provider出站。 */
    RECONCILE_ONLY
}
