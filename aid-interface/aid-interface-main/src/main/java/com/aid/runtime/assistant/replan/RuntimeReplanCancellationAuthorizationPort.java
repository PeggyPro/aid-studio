package com.aid.runtime.assistant.replan;

/**
 * Core-owned transactional port. Implementations lock session, run, then the active replan intent,
 * verify the current run lease owner/fence/expiry and return only canonical persisted identity.
 */
public interface RuntimeReplanCancellationAuthorizationPort {
    ReplanCancellationIdentity lockAndVerify(ReplanCancellationAuthorizationRequest request);

    record ReplanCancellationAuthorizationRequest(Long runId,
                                                  Long sessionId,
                                                  Long userId,
                                                  String leaseOwner,
                                                  Long expectedRunFencingToken,
                                                  Long intentId,
                                                  Long intentStateRevision,
                                                  Long commandId) { }

    record ReplanCancellationIdentity(Long runId,
                                      Long sessionId,
                                      Long intentId,
                                      Long intentStateRevision,
                                      Long commandId,
                                      String contextDigest) { }
}
