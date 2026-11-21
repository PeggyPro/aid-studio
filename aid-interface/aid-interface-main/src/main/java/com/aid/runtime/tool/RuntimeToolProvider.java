package com.aid.runtime.tool;

import com.aid.billing.dto.BillingBudgetContext;
import com.aid.runtime.service.RuntimeToolRegistry;

import java.util.List;

/** Server-owned Tool provider SPI. Providers never receive browser tokens or sessions. */
public interface RuntimeToolProvider
{
    List<RuntimeToolRegistry.Descriptor> descriptors();

    /** Technical defaults owned by the provider, opt-in only for host-compiled native calls.
     * Business choices, authorization, model identities and paid retry limits must not be declared here.
     * The canonical Tool contract and existing callers remain unchanged.
     */
    default java.util.Map<String, Object> hostOwnedInputDefaults(RuntimeToolRegistry.Descriptor descriptor) {
        return java.util.Map.of();
    }

    /** Wrapper fields governed by the Tool contract, not by a model's parameter catalog.
     * This preserves their existing validation; it does not supply values or grant authority.
     */
    default java.util.Set<String> capabilityIndependentInputFields(RuntimeToolRegistry.Descriptor descriptor) {
        return java.util.Set.of();
    }


    /** Discovery metadata only; target receipts are not finalized Runtime artifacts. */
    enum OutputReferenceKind { UNKNOWN, RUNTIME_ARTIFACT, TARGET_REFERENCE, OBSERVATION, DURABLE_RECEIPT }

    /** Exact archived artifact type, only when all outputs have one invariant type.
     * Null means dynamic/unknown; callers must retain the explicit business choice.
     */
    default String planningArtifactType(RuntimeToolRegistry.Descriptor descriptor) { return null; }

    /** Opt-in for local execution that archives artifacts under the existing step lease.
     * Persist submission intent before entering the provider; recovery reconciles its receipt.
     */
    default boolean requiresExecutionCheckpoint(RuntimeToolRegistry.Descriptor descriptor) { return false; }
    /** Optional server-owned public activity noun. Null preserves the existing presentation. */
    default String activitySubject(RuntimeToolRegistry.Descriptor descriptor) { return null; }

    /** Server-owned declaration for adapters sharing an existing media billing pipeline. */
    enum BillingMediaType { TEXT, IMAGE, VIDEO, AUDIO, MUSIC }

    default BillingMediaType billingMediaType(RuntimeToolRegistry.Descriptor descriptor)
    {
        return null;
    }

    /** Explicit model catalog scope; fixed-Skill or otherwise restricted adapters must not opt in. */
    default BillingMediaType modelCatalogMediaType(RuntimeToolRegistry.Descriptor descriptor)
    {
        return null;
    }

    /** Pure catalog projection: only expose models/capabilities this adapter can prepare. */
    default com.aid.model.vo.AiModelVO projectModelCatalog(RuntimeToolRegistry.Descriptor descriptor,
                                                         com.aid.model.vo.AiModelVO model) {
        return model;
    }

    /** Model-specific canonical Tool fields, using already loaded metadata only. */
    default java.util.Map<String,Object> modelCatalogInputSupport(RuntimeToolRegistry.Descriptor descriptor,
                                                                 com.aid.model.vo.AiModelVO model) {
        return java.util.Map.of();
    }

    /** A processing task may persist as COMPOSE while producing a VIDEO artifact. */
    default String persistedMediaType(RuntimeToolRegistry.Descriptor descriptor) {
        BillingMediaType media = billingMediaType(descriptor);
        return media == null ? null : media == BillingMediaType.MUSIC ? "AUDIO" : media.name();
    }

    /** Pure comparison of a prepared business effect, never a new execution or authorization. */
    default String completedOperationIdentity(RuntimeToolRegistry.Descriptor descriptor,
                                              String argumentsJson, String preparedPayloadJson) {
        // Preserve explicit nulls by default; only the owning adapter knows domain equivalences.
        return cn.hutool.crypto.SecureUtil.sha256(argumentsJson);
    }

    default OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor)
    {
        return OutputReferenceKind.UNKNOWN;
    }

    default RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor)
    {
        return RuntimeToolAvailability.enabled();
    }

    default RuntimeToolBudgetOffer budgetOffer(RuntimeToolAvailabilityContext context,
                                               RuntimeToolRegistry.Descriptor descriptor)
    {
        List<String> media = descriptor.requiredCapabilities().stream()
                .filter(List.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "MUSIC")::contains).toList();
        List<String> capabilities = descriptor.requiredCapabilities().stream()
                .filter(value -> !media.contains(value)).toList();
        return new RuntimeToolBudgetOffer(List.of(), capabilities, media);
    }

    default RuntimeToolPreparation prepare(RuntimeToolInvocation invocation)
    {
        return new RuntimeToolPreparation(invocation.getArgumentsJson(), null, invocation.getOperationScope());
    }

    RuntimeToolResult execute(RuntimeToolInvocation invocation);

    /** Release only local admission records after the fenced caller proves no submission occurred.
     * Must be idempotent; an accepted or unknown external job must reject this operation.
     */
    default void releaseUnsubmitted(RuntimeToolPreparedInvocation prepared) { }

    /** Read-only lookup of a durable Provider receipt. It must never repeat the side effect. */
    default RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared)
    {
        return RuntimeToolResult.unknown("TOOL_RECEIPT_NOT_FOUND", "\u64cd\u4f5c\u56de\u6267\u5f85\u5bf9\u8d26");
    }

    enum SubmissionEntryEvidence { UNKNOWN, NO_ENTRY_PROVEN }

    /**
     * Lookup only, inside the caller's fenced Runtime transaction. NO_ENTRY_PROVEN requires
     * a durable adapter intent committed BEFORE any media submission, whose entire operation
     * namespace is absent under the same locks used by first entry. Missing provider IDs,
     * elapsed time or an empty task lookup alone are never proof. The caller separately checks
     * task and billing namespaces. Implementations must not prepare, execute or contact a provider.
     */
    default SubmissionEntryEvidence inspectSubmissionEntry(RuntimeToolPreparedInvocation prepared,
                                                           BillingBudgetContext budget)
    {
        return SubmissionEntryEvidence.UNKNOWN;
    }

    default RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                               BillingBudgetContext billingBudgetContext)
    {
        RuntimeToolInvocation invocation = new RuntimeToolInvocation();
        invocation.setRunId(prepared.getRunId());
        invocation.setUserId(prepared.getUserId());
        invocation.setRuntimeStepId(prepared.getRuntimeStepId());
        invocation.setPlanRevision(prepared.getPlanRevision());
        invocation.setStableStepId(prepared.getStableStepId());
        invocation.setAttemptNo(prepared.getAttemptNo());
        invocation.setOperationKind(prepared.getOperationKind());
        invocation.setToolCode(prepared.getToolCode());
        invocation.setContractVersion(prepared.getContractVersion());
        invocation.setSchemaDigest(prepared.getSchemaDigest());
        invocation.setArgumentsJson(prepared.getPreparedPayloadJson());
        invocation.setArgumentsDigest(prepared.getPreparedPayloadDigest());
        invocation.setContextManifestJson(prepared.getContextManifestJson());
        invocation.setApprovalId(prepared.getApprovalId());
        invocation.setApprovedScopeDigest(prepared.getApprovedScopeDigest());
        invocation.setIdempotencyKey(prepared.getIdempotencyKey());
        invocation.setLease(prepared.getStepLease());
        invocation.setOperationScope(prepared.getOperationScope());
        invocation.setBillingBudgetContext(billingBudgetContext);
        return execute(invocation);
    }
    /** Read-only current permission/target/model identity checks. Never enters prepare/execute. */
    default void validateQuoteReadOnly(RuntimeToolQuoteRequest request) {
        throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_NOT_SUPPORTED");
    }

    /** Pure price preview from an immutable, already prepared request. No execution lease exists. */
    default RuntimeToolPreparation repricePreparedQuote(RuntimeToolQuoteRequest request) {
        throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_NOT_SUPPORTED");
    }


    /** Current reference evidence only; neither price preview nor execution/admission. */
    default void validatePreparedReferencesReadOnly(com.aid.runtime.tool.RuntimeToolQuoteRequest source) {
        var payload=com.alibaba.fastjson2.JSON.parseObject(source.preparedPayloadJson());
        if(payload!=null && payload.containsKey("serverResourceEvidence"))
            throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
    }
}
