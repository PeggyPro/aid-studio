package com.aid.runtime.tool;

/** Immutable server-owned quote input. Deliberately has no lease or execution budget context. */
public record RuntimeToolQuoteRequest(Long runId, Long userId, Long runtimeStepId,
                                     Long planRevision, String stableStepId, Integer attemptNo,
                                     String operationKind, String toolCode, String contractVersion,
                                     String schemaDigest, String argumentsJson, String argumentsDigest,
                                     String contextManifestJson, String approvalScopeDigest,
                                     String idempotencyKey, String operationScopeJson,
                                     String preparedPayloadJson, String preparedPayloadDigest,
                                     String billingIntentJson, String referenceRevalidationJson,
                                     java.util.Map<String,com.aid.media.dto.AuthorizedImageAssetSnapshot> authorizedQuoteImages) {
    public RuntimeToolQuoteRequest {
        authorizedQuoteImages = authorizedQuoteImages == null ? java.util.Map.of() : java.util.Map.copyOf(authorizedQuoteImages);
    }
    public RuntimeToolQuoteRequest(Long runId,Long userId,Long runtimeStepId,Long planRevision,String stableStepId,Integer attemptNo,
            String operationKind,String toolCode,String contractVersion,String schemaDigest,String argumentsJson,String argumentsDigest,
            String contextManifestJson,String approvalScopeDigest,String idempotencyKey,String operationScopeJson,
            String preparedPayloadJson,String preparedPayloadDigest,String billingIntentJson,String referenceRevalidationJson) {
        this(runId,userId,runtimeStepId,planRevision,stableStepId,attemptNo,operationKind,toolCode,contractVersion,schemaDigest,
                argumentsJson,argumentsDigest,contextManifestJson,approvalScopeDigest,idempotencyKey,operationScopeJson,
                preparedPayloadJson,preparedPayloadDigest,billingIntentJson,referenceRevalidationJson,java.util.Map.of());
    }
    public RuntimeToolQuoteRequest withReferenceRevalidation(String evidence) {
        return new RuntimeToolQuoteRequest(runId,userId,runtimeStepId,planRevision,stableStepId,attemptNo,
                operationKind,toolCode,contractVersion,schemaDigest,argumentsJson,argumentsDigest,contextManifestJson,
                approvalScopeDigest,idempotencyKey,operationScopeJson,preparedPayloadJson,preparedPayloadDigest,billingIntentJson,evidence,authorizedQuoteImages);
    }
    /** Only the resource authorization adapter may provide these current persisted snapshots. */
    public RuntimeToolQuoteRequest withAuthorizedQuoteImages(java.util.Map<String,com.aid.media.dto.AuthorizedImageAssetSnapshot> images) {
        return new RuntimeToolQuoteRequest(runId,userId,runtimeStepId,planRevision,stableStepId,attemptNo,
                operationKind,toolCode,contractVersion,schemaDigest,argumentsJson,argumentsDigest,contextManifestJson,
                approvalScopeDigest,idempotencyKey,operationScopeJson,preparedPayloadJson,preparedPayloadDigest,
                billingIntentJson,referenceRevalidationJson,images);
    }
}
