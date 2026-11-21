package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.util.AssistantMediaQuoteDigest;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasGenerationService;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.canvas.service.impl.CanvasGenerationServiceImpl;
import com.aid.common.exception.ServiceException;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.domain.ComposeCommand;
import com.aid.compose.domain.ComposeGroup;
import com.aid.compose.service.RuntimeComposeTaskService;
import com.aid.runtime.assistant.media.AssistantMediaRuntimeToolProvider;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Generic registered-resource composition. No Skill names, arbitrary URLs or provider arguments. */
@Component
@RequiredArgsConstructor
public class CanvasAssistantComposeToolProvider implements RuntimeToolProvider {
    public static final String CODE = "canvas.media.compose_video";
    public static final String CLIP_CODE = "canvas.media.compose_video_clip";
    private static final Set<String> CODES = Set.of(CODE, CLIP_CODE);
    private static final String SCHEMA = "canvas-compose-prepared/v1";
    private static final String INPUT = """
        {"type":"object","required":["target","groups"],"properties":{
          "target":{"type":"object","required":["objectId","objectVersion"],"properties":{
            "objectId":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},
            "objectVersion":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},"additionalProperties":false},
          "groups":{"type":"array","minItems":1,"maxItems":50,"items":{"type":"object","required":["videoResourceIds"],"properties":{
            "videoResourceIds":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},
            "audioResourceIds":{"type":"array","maxItems":16,"items":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},
            "subtitle":{"type":["string","null"],"maxLength":10000},
            "bgmResourceId":{"type":["string","null"],"pattern":"^[1-9][0-9]{0,18}$"}},"additionalProperties":false}},
          "globalBgmResourceId":{"type":["string","null"],"pattern":"^[1-9][0-9]{0,18}$"},
          "alignStrategy":{"enum":["VIDEO","AUDIO",null]},
          "resolution":{"enum":["SD","HD","FHD","2K","4K",null]}
        },"additionalProperties":false}
        """;
    private static final String OUTPUT = """
        {"type":"object","required":["mediaTaskId","status","mediaType","modelCode"],"properties":{
          "mediaTaskId":{"type":"string"},"status":{"type":"string"},"mediaType":{"enum":["VIDEO"]},
          "modelCode":{"type":"string"}},"additionalProperties":false}
        """;
    private final RuntimeComposeTaskService compose;
    private final MpsConfigManager config;
    private final CanvasAccessService access;
    private final CanvasResourceService resources;
    private final CanvasMapper canvas;
    private final RuntimeScopeAccess scopes;
    private final CanvasGenerationService generation;
    private final AssistantMediaRuntimeToolProvider mediaResults;
    private final com.aid.media.service.VerifiedMediaMetadataService metadata;

    @Override public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(descriptor(CODE, INPUT, "合成画布视频", "Compose ordered registered videos, optional registered audio/BGM and subtitles into one exact VIDEO node. "
                +"Use existing video resource IDs from the current workspace; never wait on or replace an UNKNOWN generation task. "
                +"Registered source durations are required. Source audio is muted; only explicit audio/BGM plays. "
                +"Compose once, then use candidates/adopt to select its result on a node that already has another selected result. "
                +"Uses the configured media-processing engine, existing pricing and the current run budget; no AI model selection is required."),
                descriptor(CLIP_CODE, clipInput(), "精确取用画布视频", "Take an explicit prefix of every registered video at original speed and compose into one exact VIDEO node. "
                +"groups.videoTakeSeconds must match videoResourceIds in order and count; the host verifies each selection against actual source duration. "
                +"Set sourceAudioEnabled=true to preserve source sound; otherwise only explicit audio/BGM plays. "
                +"Use alignStrategy=VIDEO for exact selected video lengths. Preserve original results. If the target already has a selected result, use candidates/adopt after completion. "
                +"Uses the existing processing engine, quotation and task system; no AI video generation or new node is required."));
    }
    private static RuntimeToolRegistry.Descriptor descriptor(String code,String input,String name,String description) {
        return new RuntimeToolRegistry.Descriptor(code,"1.0.0",SecureUtil.sha256(input+"\n"+OUTPUT),name,description,
                input,OUTPUT,"BILLABLE",true,"IDEMPOTENCY_KEY",false,"RECONCILE_TASK",List.of("CANVAS_WRITE","VIDEO"));
    }
    private static String clipInput() {
        var root=JSON.parseObject(INPUT);
        var group=root.getJSONObject("properties").getJSONObject("groups").getJSONObject("items");
        group.getJSONArray("required").add("videoTakeSeconds");
        group.getJSONObject("properties").put("videoTakeSeconds",JSONObject.of("type","array","minItems",1,"maxItems",16,
                "items",JSONObject.of("type","number","minimum",0.001,"maximum",3600)));
        root.getJSONObject("properties").put("sourceAudioEnabled",JSONObject.of("type",com.alibaba.fastjson2.JSONArray.of("boolean","null")));
        return root.toJSONString();
    }
    @Override public BillingMediaType billingMediaType(RuntimeToolRegistry.Descriptor descriptor) { return BillingMediaType.VIDEO; }
    @Override public String persistedMediaType(RuntimeToolRegistry.Descriptor descriptor) { return "COMPOSE"; }
    @Override public String completedOperationIdentity(RuntimeToolRegistry.Descriptor descriptor,
                                                       String argumentsJson, String preparedPayloadJson) {
        var args=JSON.parseObject(argumentsJson);
        var quote=readQuote(preparedPayloadJson);
        // Compare the actual business command; nullable/omitted optional inputs become the same command.
        // A task/quote ID or an automatically advanced target fence is not a new requested effect.
        return AssistantMediaQuoteDigest.requestDigest(Map.of("command",quote.preparation().command(),
                "targetObjectId",args.getJSONObject("target").getString("objectId"),
                "engineCode",quote.preparation().engineCode()));
    }
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) { return OutputReferenceKind.RUNTIME_ARTIFACT; }

    @Override public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context, RuntimeToolRegistry.Descriptor descriptor) {
        if(context==null || context.getUserId()==null || context.getWorkspaceId()==null) return RuntimeToolAvailability.unavailable("CANVAS_COMPOSE_SCOPE_MISSING");
        var refs=CanvasAssistantContextManifest.availableRefs(context.getContextManifestJson(),context.getProjectId(),context.getWorkspaceId());
        if(refs.mutableTargets()==0 && !(context.isBudgetDiscovery() && refs.mutableWorkspace())) return RuntimeToolAvailability.unavailable("CANVAS_TARGET_WRITE_MISSING");
        var workspace=access.requireWorkspace(context.getWorkspaceId(),context.getUserId(),"OWNER","EDITOR");
        access.requireFeature(context.getUserId(),workspace.getProjectId(),"canvas.generate");
        return Boolean.TRUE.equals(config.getMpsProperties().getEnabled()) ? RuntimeToolAvailability.enabled()
                : RuntimeToolAvailability.unavailable("CANVAS_COMPOSE_DISABLED");
    }
    @Override public RuntimeToolBudgetOffer budgetOffer(RuntimeToolAvailabilityContext context, RuntimeToolRegistry.Descriptor descriptor) {
        if(!availability(context,descriptor).available()) return new RuntimeToolBudgetOffer();
        return new RuntimeToolBudgetOffer(List.of(config.getMpsProperties().getProcessMode()),List.of("media.compose"),List.of("VIDEO"));
    }

    @Override public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        try { return prepareValidated(invocation); }
        catch (ServiceException rejected) {
            String code = rejected.getDetailMessage();
            if (code == null || !(code.startsWith("CANVAS_COMPOSE_") || code.startsWith("CANVAS_TARGET_")
                    || code.startsWith("CANVAS_MANIFEST_") || code.startsWith("CANVAS_REVISION_"))) throw rejected;
            var reason = "CANVAS_COMPOSE_METADATA_PREPARATION_REQUIRED".equals(code)
                    ? RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_METADATA_REQUIRED
                    : RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID;
            throw new RuntimeToolPreparationRejectedException(reason, rejected);
        }
    }

    private RuntimeToolPreparation prepareValidated(RuntimeToolInvocation invocation) {
        if(invocation==null || !CODES.contains(invocation.getToolCode()) || invocation.getRunId()==null
                || invocation.getUserId()==null || invocation.getIdempotencyKey()==null) throw failure("CANVAS_COMPOSE_IDENTITY_INVALID");
        if(invocation.getOperationScope()==null
                || !Objects.equals(invocation.getToolCode(),invocation.getOperationScope().getToolCode())) throw failure("CANVAS_COMPOSE_SCOPE_INVALID");
        JSONObject args=JSON.parseObject(invocation.getArgumentsJson());
        var target=target(invocation,args);
        validateTarget(invocation.getUserId(),target);
        ComposeCommand command=command(args,target,invocation.getUserId());
        return preparation(compose.prepare(command),invocation.getOperationScope(),target);
    }

    private RuntimeToolPreparation preparation(RuntimeComposeTaskService.Quote quote, AssistantBudgetOperationScope source,
                                                CanvasAssistantContextManifest.TargetRef target) {
        if(source==null || !CODES.contains(source.getToolCode())) throw failure("CANVAS_COMPOSE_SCOPE_INVALID");
        var scope=new AssistantBudgetOperationScope();scope.setOperationKind(source.getOperationKind());scope.setToolCode(source.getToolCode());
        scope.setCountsAsIteration(source.isCountsAsIteration());scope.setModelCode(quote.preparation().engineCode());
        scope.setCapabilityCodes(List.of("media.compose"));scope.setMediaTypes(List.of("VIDEO"));
        scope.setTargetRefs(CanvasAssistantMediaToolProvider.scopeTargetRefs(target));scope.setQuotedUpperBound(quote.upperBound());
        var intent=new RuntimeToolBillingIntent();intent.setMediaType("COMPOSE");
        intent.setNormalizedRequestJson(JSON.toJSONString(quote.preparation()));intent.setRequestDigest(quote.requestDigest());
        intent.setQuoteId(quote.quoteId());intent.setQuoteDigest(quote.quoteDigest());intent.setQuoteExpiresAt(quote.expiresAt());intent.setQuotedUpperBound(quote.upperBound());
        JSONObject payload=new JSONObject();payload.put("schemaVersion",SCHEMA);payload.put("quote",quote);
        return new RuntimeToolPreparation(payload.toJSONString(),intent,scope);
    }

    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation) { throw failure("CANVAS_COMPOSE_PREPARED_REQUIRED"); }
    @Override public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared, BillingBudgetContext budget) {
        var quote=requirePrepared(prepared,budget);
        var invocation=CanvasAssistantToolProvider.invocation(prepared);invocation.setArgumentsJson(prepared.getArgumentsJson());
        var target=target(invocation,JSON.parseObject(prepared.getArgumentsJson()));
        String operation=CanvasGenerationServiceImpl.assistantMediaOperationId(prepared.getRunId(),prepared.getIdempotencyKey());
        var existing=canvas.selectTaskBindingByOperation(target.workspaceId(),operation);
        if(existing!=null && existing.getTaskId()!=null) return reconcilePrepared(prepared);
        validateTarget(prepared.getUserId(),target);
        // All current-source and quote checks precede creation of the durable submission intent.
        if(existing==null) {
            try { requireCurrentResources(prepared.getArgumentsJson(),target,prepared.getUserId(),quote);compose.validate(quote); }
            catch(RuntimeException rejected) {throw new com.aid.media.admission.MediaSubmissionNotEnteredException("CANVAS_COMPOSE_NOT_ENTERED",rejected,budget);}
        }
        var lease=new RuntimeScopeAccess.Lease(prepared.getRunLease().getOwner(),prepared.getRunLease().getFencingToken(),prepared.getRunLease().getLeaseUntil());
        RuntimeScopeAccess.Scope scope;
        try {
            scope=existing==null ? scopes.reserveTarget(prepared.getRunId(),prepared.getUserId(),lease,target.projectId(),target.workspaceId(),target.objectId(),target.objectVersion())
                    : scopes.authorizeExactTarget(prepared.getRunId(),prepared.getUserId(),lease,target.projectId(),target.workspaceId(),target.objectId(),target.objectVersion());
            if(existing!=null) scope=new RuntimeScopeAccess.Scope(scope.runId(),scope.userId(),scope.projectId(),scope.workspaceId(),scope.objectId(),scope.objectVersion(),
                    scope.leaseOwner(),scope.runtimeFencingToken(),scope.leaseUntil(),canvas.selectObjectFence(scope.workspaceId(),scope.objectId()));
        } catch(RuntimeException rejected) {
            if(existing!=null) throw rejected;
            throw new com.aid.media.admission.MediaSubmissionNotEnteredException("CANVAS_SCOPE_NOT_ENTERED",rejected,budget);
        }
        try {
            var response=generation.submitAssistantMedia(scope,CanvasAssistantToolProvider.submissionClaim(prepared),"VIDEO",prepared.getArgumentsDigest(),()->{
                requireCurrentResources(prepared.getArgumentsJson(),target,prepared.getUserId(),quote);
                var task=compose.submit(quote,budget,BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE);
                return com.aid.media.dto.MediaTaskResponse.builder().taskId(task.getId()).status(task.getStatus())
                        .mediaType("VIDEO").modelName(task.getModelName()).build();
            });
            return mediaResults.scopedTaskResult(prepared,response);
        } finally { scopes.release(scope); }
    }

    @Override public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared) {
        var invocation=CanvasAssistantToolProvider.invocation(prepared);invocation.setArgumentsJson(prepared.getArgumentsJson());
        var target=target(invocation,JSON.parseObject(prepared.getArgumentsJson()));
        String operation=CanvasGenerationServiceImpl.assistantMediaOperationId(prepared.getRunId(),prepared.getIdempotencyKey());
        var binding=canvas.selectTaskBindingByOperation(target.workspaceId(),operation);
        if(binding==null || binding.getTaskId()==null) return RuntimeToolResult.unknown("CANVAS_COMPOSE_RECEIPT_PENDING","合成提交回执待核实");
        if(!Objects.equals(binding.getActorUserId(),prepared.getUserId()) || !Objects.equals(binding.getCallId(),prepared.getIdempotencyKey())
                || !Objects.equals(binding.getProjectId(),target.projectId()) || !Objects.equals(binding.getWorkspaceId(),target.workspaceId())
                || !Objects.equals(binding.getRequestHash(),prepared.getArgumentsDigest()) || !Objects.equals(binding.getObjectId(),target.objectId())
                || !Objects.equals(binding.getInputObjectVersion(),target.objectVersion()) || !Objects.equals(binding.getMediaType(),"VIDEO")) throw failure("CANVAS_COMPOSE_BINDING_CHANGED");
        return mediaResults.scopedTaskResult(prepared,generation.taskDetail(target.workspaceId(),binding.getTaskId(),prepared.getUserId()));
    }

    private RuntimeComposeTaskService.Quote requirePrepared(RuntimeToolPreparedInvocation prepared,BillingBudgetContext budget) {
        var quote=readQuote(prepared.getPreparedPayloadJson());var intent=prepared.getBillingIntent();
        var invocation=CanvasAssistantToolProvider.invocation(prepared);
        var target=target(invocation,JSON.parseObject(prepared.getArgumentsJson()));
        if(!CODES.contains(prepared.getToolCode()) || intent==null || budget==null || !budget.isRunAuthorizationMode()
                || prepared.getOperationScope()==null || !List.of("VIDEO").equals(prepared.getOperationScope().getMediaTypes())
                || !Objects.equals(prepared.getOperationScope().getTargetRefs(),CanvasAssistantMediaToolProvider.scopeTargetRefs(target))
                || !Objects.equals(prepared.getOperationScope().getModelCode(),quote.preparation().engineCode())
                || prepared.getOperationScope().getQuotedUpperBound()==null || quote.upperBound()==null
                || prepared.getOperationScope().getQuotedUpperBound().compareTo(quote.upperBound())!=0
                || !Objects.equals(quote.preparation().command().getProjectId(),target.projectId())
                || !"COMPOSE".equals(intent.getMediaType()) || !Objects.equals(intent.getRequestDigest(),quote.requestDigest())
                || !Objects.equals(intent.getQuoteId(),quote.quoteId()) || !Objects.equals(intent.getQuoteDigest(),quote.quoteDigest())
                || !Objects.equals(intent.getQuoteExpiresAt(),quote.expiresAt()) || !Objects.equals(intent.getNormalizedRequestJson(),JSON.toJSONString(quote.preparation()))
                || !Objects.equals(budget.getRequestDigest(),quote.requestDigest()) || !Objects.equals(budget.getQuoteDigest(),quote.quoteDigest())
                || !Objects.equals(budget.getLogicalCallKey(),prepared.getIdempotencyKey())
                || !Objects.equals(budget.getRuntimeRunId(),prepared.getRunId()) || !Objects.equals(budget.getRuntimeStepId(),prepared.getRuntimeStepId())
                || !Objects.equals(quote.preparation().command().getUserId(),prepared.getUserId())) throw failure("CANVAS_COMPOSE_QUOTE_CHANGED");
        return quote;
    }

    @Override public SubmissionEntryEvidence inspectSubmissionEntry(RuntimeToolPreparedInvocation prepared,BillingBudgetContext budget) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw failure("CANVAS_COMPOSE_ENTRY_TRANSACTION_REQUIRED");
        requirePrepared(prepared,budget);
        var invocation=CanvasAssistantToolProvider.invocation(prepared);var args=JSON.parseObject(prepared.getArgumentsJson());
        var target=target(invocation,args);String operation=CanvasGenerationServiceImpl.assistantMediaOperationId(prepared.getRunId(),prepared.getIdempotencyKey());
        if(canvas.selectTaskBindingByOperationForUpdate(target.workspaceId(),operation)!=null) return SubmissionEntryEvidence.UNKNOWN;
        var conflicts=canvas.selectAssistantMediaEntryNamespace(operation,prepared.getIdempotencyKey());
        return conflicts!=null && conflicts.isEmpty() ? SubmissionEntryEvidence.NO_ENTRY_PROVEN : SubmissionEntryEvidence.UNKNOWN;
    }

    @Override public void validateQuoteReadOnly(RuntimeToolQuoteRequest request) {
        var quote=readQuote(request.preparedPayloadJson());var invocation=new RuntimeToolInvocation();
        invocation.setContextManifestJson(request.contextManifestJson());var target=target(invocation,JSON.parseObject(request.argumentsJson()));
        validateTarget(request.userId(),target);requireCurrentResources(request.argumentsJson(),target,request.userId(),quote);
        var scope=JSON.parseObject(request.operationScopeJson(),AssistantBudgetOperationScope.class);
        if(scope==null || !Objects.equals(scope.getTargetRefs(),CanvasAssistantMediaToolProvider.scopeTargetRefs(target))) throw failure("CANVAS_COMPOSE_TARGET_CHANGED");
    }
    @Override public void validatePreparedReferencesReadOnly(RuntimeToolQuoteRequest request) { validateQuoteReadOnly(request); }
    @Override public RuntimeToolPreparation repricePreparedQuote(RuntimeToolQuoteRequest request) {
        validateQuoteReadOnly(request);var invocation=new RuntimeToolInvocation();invocation.setContextManifestJson(request.contextManifestJson());
        var target=target(invocation,JSON.parseObject(request.argumentsJson()));
        return preparation(compose.reprice(readQuote(request.preparedPayloadJson()).preparation()),
                JSON.parseObject(request.operationScopeJson(),AssistantBudgetOperationScope.class),target);
    }

    private RuntimeComposeTaskService.Quote readQuote(String payloadJson) {
        JSONObject payload=JSON.parseObject(payloadJson);
        if(payload==null || !SCHEMA.equals(payload.getString("schemaVersion"))) throw failure("CANVAS_COMPOSE_SNAPSHOT_INVALID");
        var quote=payload.getObject("quote",RuntimeComposeTaskService.Quote.class);
        if(quote==null || quote.preparation()==null || quote.preparation().command()==null) throw failure("CANVAS_COMPOSE_SNAPSHOT_INVALID");
        var expected=RuntimeComposeTaskService.quote(quote.preparation(),quote.quoteId(),quote.expiresAt());
        if(!Objects.equals(expected.quoteDigest(),quote.quoteDigest()) || !Objects.equals(expected.requestDigest(),quote.requestDigest())) throw failure("CANVAS_COMPOSE_SNAPSHOT_CHANGED");
        return quote;
    }
    private void requireCurrentResources(String arguments,CanvasAssistantContextManifest.TargetRef target,Long user,RuntimeComposeTaskService.Quote quote) {
        ComposeCommand current=command(JSON.parseObject(arguments),target,user);
        // Internal execution policy is frozen with the quote; a new default must not invalidate old receipts.
        current.setSourceAudioEnabled(quote.preparation().command().getSourceAudioEnabled());
        if(!Objects.equals(AssistantMediaQuoteDigest.requestDigest(current),AssistantMediaQuoteDigest.requestDigest(quote.preparation().command()))) throw failure("CANVAS_COMPOSE_RESOURCES_CHANGED");
    }
    private CanvasAssistantContextManifest.TargetRef target(RuntimeToolInvocation invocation,JSONObject args) {
        JSONObject target=args==null ? null : args.getJSONObject("target");if(target==null) throw failure("CANVAS_TARGET_INVALID");
        return CanvasAssistantContextManifest.requireTarget(invocation,target.getString("objectId"),target.getString("objectVersion"),true);
    }
    private void validateTarget(Long user,CanvasAssistantContextManifest.TargetRef target) {
        var workspace=access.requireWorkspace(target.workspaceId(),user,"OWNER","EDITOR");access.requireFeature(user,target.projectId(),"canvas.generate");
        var node=canvas.selectCanvasObject(target.workspaceId(),target.objectId());
        if(!Objects.equals(workspace.getProjectId(),target.projectId()) || node==null || !Objects.equals(node.getDeleted(),0)
                || !Objects.equals(node.getObjectVersion(),target.objectVersion()) || !"NODE".equals(node.getObjectType())
                || !"VIDEO".equals(JSON.parseObject(node.getContentJson()).getString("nodeType"))) throw failure("CANVAS_COMPOSE_TARGET_INVALID");
    }

    private ComposeCommand command(JSONObject args,CanvasAssistantContextManifest.TargetRef target,Long user) {
        var groups=args.getJSONArray("groups");if(groups==null || groups.isEmpty() || groups.size()>50) throw failure("CANVAS_COMPOSE_GROUPS_INVALID");
        LinkedHashSet<Long> ids=new LinkedHashSet<>();
        for(Object raw:groups) {
            if(!(raw instanceof JSONObject group)) throw failure("CANVAS_COMPOSE_GROUPS_INVALID");
            ids.addAll(ids(group,"videoResourceIds",true));ids.addAll(ids(group,"audioResourceIds",false));
            if(group.getString("bgmResourceId")!=null) ids.add(id(group.getString("bgmResourceId")));
        }
        if(args.getString("globalBgmResourceId")!=null) ids.add(id(args.getString("globalBgmResourceId")));
        if(ids.size()>100) throw failure("CANVAS_COMPOSE_TOO_MANY_RESOURCES");
        var available=resources.requireResources(target.workspaceId(),new ArrayList<>(ids),user);Map<Long,CanvasResource> map=new HashMap<>();
        for(var resource:available) {
            if(resource==null || !"ACTIVE".equals(resource.getStatus()) || !Objects.equals(resource.getProjectId(),target.projectId())
                    || !Objects.equals(resource.getWorkspaceId(),target.workspaceId()) || resource.getStorageUrl()==null || resource.getStorageUrl().isBlank()) throw failure("CANVAS_COMPOSE_RESOURCE_UNAVAILABLE");
            map.put(resource.getId(),resource);
        }
        var command=new ComposeCommand();command.setUserId(user);command.setProjectId(target.projectId());
        command.setSourceAudioEnabled(Boolean.TRUE.equals(args.getBoolean("sourceAudioEnabled")));
        command.setAlignStrategy(args.getString("alignStrategy"));command.setResolution(args.getString("resolution"));
        List<ComposeGroup> result=new ArrayList<>();
        Map<Long,java.math.BigDecimal> verifiedDurations=new HashMap<>();
        for(Object raw:groups) {
            JSONObject row=(JSONObject)raw;var group=new ComposeGroup();
            var video=ids(row,"videoResourceIds",true).stream().map(value->resource(map,value,"VIDEO")).toList();
            group.setVideoUrls(video.stream().map(CanvasResource::getStorageUrl).toList());
            group.setVideoDurations(CanvasComposeClipDurations.resolve(video.stream().map(value -> duration(value, verifiedDurations)).toList(),
                    row.getJSONArray("videoTakeSeconds"), index -> {
                        CanvasResource source=video.get(index);
                        return verifiedDurations.computeIfAbsent(source.getId(),
                                ignored -> metadata.inspect(source.getStorageUrl(),"video").durationSeconds());
                    }));
            var audio=ids(row,"audioResourceIds",false).stream().map(value->resource(map,value,"AUDIO")).toList();
            group.setAudioUrls(audio.stream().map(CanvasResource::getStorageUrl).toList());group.setAudioDurations(audio.stream().map(value -> duration(value, verifiedDurations)).toList());
            group.setSubtitle(row.getString("subtitle"));
            if(row.getString("bgmResourceId")!=null) group.setBgmUrl(resource(map,id(row.getString("bgmResourceId")),"AUDIO").getStorageUrl());
            result.add(group);
        }
        if(args.getString("globalBgmResourceId")!=null) command.setGlobalBgmUrl(resource(map,id(args.getString("globalBgmResourceId")),"AUDIO").getStorageUrl());
        command.setGroups(result);return command;
    }
    private List<Long> ids(JSONObject row,String key,boolean required) {
        var array=row.getJSONArray(key);if(array==null || array.isEmpty()) {if(required) throw failure("CANVAS_COMPOSE_VIDEO_REQUIRED");return List.of();}
        if(array.size()>16) throw failure("CANVAS_COMPOSE_TOO_MANY_CLIPS");List<Long> ids=new ArrayList<>();
        for(Object value:array) {if(!(value instanceof String text)) throw failure("CANVAS_COMPOSE_RESOURCE_INVALID");ids.add(id(text));}return ids;
    }
    private Long id(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw failure("CANVAS_COMPOSE_RESOURCE_INVALID");
        try {return Long.valueOf(value);} catch(NumberFormatException invalid) {throw failure("CANVAS_COMPOSE_RESOURCE_INVALID");}
    }
    private CanvasResource resource(Map<Long,CanvasResource> map,Long id,String type) {
        var resource=map.get(id);if(resource==null || !type.equals(resource.getMediaType())) throw failure("CANVAS_COMPOSE_RESOURCE_TYPE_INVALID");return resource;
    }
    private Double duration(CanvasResource resource, Map<Long,java.math.BigDecimal> verifiedDurations) {
        JSONObject stored=resource.getMetadataJson()==null ? null : JSON.parseObject(resource.getMetadataJson());
        Double seconds=stored==null ? null : stored.getDouble("durationSeconds");
        if(seconds==null || !Double.isFinite(seconds) || seconds<=0 || seconds>3600) {
            // Registered generated results need not duplicate probe metadata. Resolve missing
            // duration from the verified source before quotation, without inventing or persisting it.
            var actual=verifiedDurations.computeIfAbsent(resource.getId(), ignored ->
                    metadata.inspect(resource.getStorageUrl(), resource.getMediaType().toLowerCase(java.util.Locale.ROOT)).durationSeconds());
            seconds=actual==null ? null : actual.doubleValue();
        }
        if(seconds==null || !Double.isFinite(seconds) || seconds<=0 || seconds>3600) throw failure("CANVAS_COMPOSE_METADATA_PREPARATION_REQUIRED");
        return seconds;
    }
    private static ServiceException failure(String code) { return new ServiceException("视频合成暂不可执行").setDetailMessage(code); }
}
