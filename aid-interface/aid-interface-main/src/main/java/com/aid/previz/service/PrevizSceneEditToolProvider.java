package com.aid.previz.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Local document commands share Runtime approval, fencing, document CAS and undo history. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public class PrevizSceneEditToolProvider implements RuntimeToolProvider {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PrevizSceneEditToolProvider.class);
    public static final String CODE="aid.previz.scene-edit";
    public static final String INSPECT="aid.previz.scene-inspect";
    private final PrevizSceneEditService edits;
    private final PrevizProjectService projects;
    private final AidAssistantSessionMapper sessions;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeRunStepMapper steps;
    private static final String INPUT="""
        {"type":"object","required":["edits"],"properties":{"frame":{"type":"integer","minimum":0},"edits":{"type":"array","minItems":1,"maxItems":100,"items":{"type":"object","required":["field","value"],"properties":{"nodeId":{"type":"string"},"objectId":{"type":"string"},"field":{"type":"string","enum":["position","rotation","scale","color","visible","target"]},"value":{"oneOf":[{"type":"array","minItems":3,"maxItems":3,"items":{"type":"number"}},{"type":"string","pattern":"^#[a-fA-F0-9]{6}$"},{"type":"boolean"}]},"mode":{"type":"string","enum":["SET","OFFSET"]},"keyframe":{"type":"boolean"}},"additionalProperties":false}}},"additionalProperties":false}
        """;
    @Override public List<RuntimeToolRegistry.Descriptor> descriptors(){
        String readInput="{\"type\":\"object\",\"properties\":{\"nodeId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":5000},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200}},\"additionalProperties\":false}";
        return List.of(new RuntimeToolRegistry.Descriptor(INSPECT,"1.0.0",SecureUtil.sha256(readInput+"\n{\"type\":\"object\"}"),"查询当前三维场景对象","读取固定编辑快照。省略nodeId返回场景节点；指定GLB节点分页查询内部对象稳定ID、名称和父级，默认100项。只读取当前采用范围，不返回网格或源码。使用真实ID编辑，不猜测名称。",readInput,"{\"type\":\"object\"}","READ",false,"REQUEST_DIGEST",false,"REPLAY_SAFE",List.of("previz.project")),new RuntimeToolRegistry.Descriptor(CODE,"1.0.0",SecureUtil.sha256(INPUT+"\n{\"type\":\"object\"}"),
            "修改三维对象属性与关键帧","直接修改已保存场景，不重新构建源码。nodeId填写当前PREVIZ_EDITOR中的场景节点ID；只有编辑GLB内部对象时才填写objectId，值为内部hf_id。人物、相机及普通图元只填nodeId，不把nodeId重复填入objectId。未指定目标时仅使用唯一选中对象。position米、rotation弧度、scale倍率；内部变换为来源姿态上的编辑偏移。OFFSET位置和旋转做加法、缩放做乘法；默认SET。省略frame用当前帧，已有动画更新该帧，keyframe=true添加关键帧。color用#RRGGBB，visible为布尔。可用于房屋、汽车、人物及组合零件。程序绑定固定工程版本，并保护锁定目标和并发编辑；成功以文档发布回执为准。",INPUT,"{\"type\":\"object\"}","DATABASE_WRITE",false,"IDEMPOTENCY_KEY",false,"RECONCILE_RECEIPT",List.of("previz.project")));}
    @Override public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,RuntimeToolRegistry.Descriptor descriptor){return context!=null&&context.getUserId()!=null&&"PREVIZ_3DBOX".equals(context.getModuleCode())?RuntimeToolAvailability.enabled():RuntimeToolAvailability.unavailable("PREVIZ_PROJECT_REQUIRED");}
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor){return INSPECT.equals(descriptor.code())?OutputReferenceKind.OBSERVATION:OutputReferenceKind.DURABLE_RECEIPT;}
    @Override public boolean requiresExecutionCheckpoint(RuntimeToolRegistry.Descriptor descriptor){return CODE.equals(descriptor.code());}
    @Override public String activitySubject(RuntimeToolRegistry.Descriptor descriptor){return INSPECT.equals(descriptor.code())?"三维对象查询":"三维对象编辑";}
    @Override public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation){
        try {
        String project=project(invocation.getUserId(),invocation.getRunId(),invocation.getContextManifestJson());
        String editorVersion=null;var refs=JSON.parseObject(invocation.getContextManifestJson()).getJSONArray("refs");
        for(Object raw:refs)if(raw instanceof JSONObject ref&&"PREVIZ_EDITOR".equals(ref.getString("kind"))){
            var canonical=ref.getJSONObject("canonicalRef");
            if(editorVersion!=null||canonical==null||!project.equals(canonical.getString("previzProjectId")))throw new ServiceException("三维编辑上下文无效");
            editorVersion=canonical.getString("objectId");
        }
        if(editorVersion==null)throw new ServiceException("请先保存并绑定当前场景编辑状态");
        return new RuntimeToolPreparation((INSPECT.equals(invocation.getToolCode())?JSONObject.of("projectId",project,"editorVersion",editorVersion,"request",JSON.parseObject(invocation.getArgumentsJson())):edits.prepare(invocation.getUserId(),project,editorVersion,JSON.parseObject(invocation.getArgumentsJson()))).toJSONString(),null,invocation.getOperationScope());
        }catch(RuntimeToolPreparationRejectedException rejected){throw rejected;}
        catch(IllegalArgumentException invalid){LOG.warn("三维对象编辑准备拒绝: runId={}, reason={}",invocation.getRunId(),invalid.getMessage());throw new RuntimeToolPreparationRejectedException(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,invalid);}
        catch(IOException|ServiceException unavailable){throw new RuntimeToolPreparationRejectedException(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID,unavailable);}
    }
    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation){
        if(!INSPECT.equals(invocation.getToolCode()))throw new ServiceException("场景编辑必须通过已审批的执行步骤");
        String project=project(invocation.getUserId(),invocation.getRunId(),invocation.getContextManifestJson());JSONObject args=JSON.parseObject(invocation.getArgumentsJson());
        try{var result=RuntimeToolResult.succeeded(edits.inspect(invocation.getUserId(),project,args.getString("editorVersion"),args.getJSONObject("request")).toJSONString());result.setSafeSummary("已读取场景对象及稳定标识");return result;}
        catch(IOException unavailable){return failed("PREVIZ_OBJECT_READ_UNAVAILABLE","场景对象读取未完成，工程未改变");}
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,com.aid.billing.dto.BillingBudgetContext budget){
        if(INSPECT.equals(prepared.getToolCode()))return RuntimeToolProvider.super.executePrepared(prepared,budget);
        lockAndCheck(prepared,true);String project=project(prepared.getUserId(),prepared.getRunId(),prepared.getContextManifestJson());
        try{return result(edits.execute(prepared.getUserId(),project,JSON.parseObject(prepared.getPreparedPayloadJson()),operation(prepared),()->lockAndCheck(prepared,true)));}
        catch(IllegalArgumentException conflict){LOG.warn("三维对象编辑未发布: runId={}, stepId={}, reason={}",prepared.getRunId(),prepared.getRuntimeStepId(),conflict.getMessage());return failed("PREVIZ_EDIT_CONFLICT",conflict.getMessage());}
        catch(IOException unavailable){return RuntimeToolResult.unknown("PREVIZ_EDIT_RECEIPT_PENDING","编辑回执待核对，当前工程保留且不会重复执行");}
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared){
        if(INSPECT.equals(prepared.getToolCode()))return RuntimeToolProvider.super.executePrepared(prepared,null);
        lockAndCheck(prepared,false);String project=project(prepared.getUserId(),prepared.getRunId(),prepared.getContextManifestJson());
        try{JSONObject receipt=edits.reconcile(prepared.getUserId(),project,operation(prepared));return receipt==null?failed("PREVIZ_EDIT_NOT_ENTERED","经执行锁与持久记录核对，本次编辑尚未写入工程"):result(receipt);}
        catch(IOException unavailable){return RuntimeToolResult.unknown("PREVIZ_EDIT_RECEIPT_PENDING","编辑回执暂时无法核对，不重复执行");}
    }
    private void lockAndCheck(RuntimeToolPreparedInvocation p,boolean execute){
        if(p.getRunLease()==null||p.getStepLease()==null||p.getRuntimeStepId()==null||p.getPlanRevision()==null)throw new ServiceException("PREVIZ_EDIT_RUNTIME_REQUIRED");
        AidRuntimeRun discovered=runs.selectById(p.getRunId());if(discovered==null)throw new ServiceException("编辑运行不存在");
        var session=sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>().eq(AidAssistantSession::getId,discovered.getSessionId()).eq(AidAssistantSession::getDelFlag,"0").last("FOR UPDATE"));
        var run=runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>().eq(AidRuntimeRun::getId,p.getRunId()).eq(AidRuntimeRun::getDelFlag,"0").last("FOR UPDATE"));
        var step=steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>().eq(AidRuntimeRunStep::getId,p.getRuntimeStepId()).eq(AidRuntimeRunStep::getRunId,p.getRunId()).eq(AidRuntimeRunStep::getDelFlag,"0").last("FOR UPDATE"));
        Date now=new Date();var a=p.getRunLease();var b=p.getStepLease();
        if(session==null||run==null||step==null||!Objects.equals(session.getUserId(),p.getUserId())||!Objects.equals(run.getUserId(),p.getUserId())
                ||!CODE.equals(step.getToolCode())||!Objects.equals(step.getIdempotencyKey(),p.getIdempotencyKey())||!Objects.equals(step.getPlanRevision(),p.getPlanRevision())
                ||!Objects.equals(a.getOwner(),b.getOwner())||!Objects.equals(run.getLeaseOwner(),a.getOwner())||!Objects.equals(run.getFencingToken(),a.getFencingToken())
                ||!Objects.equals(step.getLeaseOwner(),b.getOwner())||!Objects.equals(step.getFencingToken(),b.getFencingToken())
                ||run.getLeaseUntil()==null||!run.getLeaseUntil().after(now)||step.getLeaseUntil()==null||!step.getLeaseUntil().after(now)
                ||execute&&(!Objects.equals(session.getActiveRuntimeRunId(),run.getId())||Boolean.TRUE.equals(run.getStopRequested())||!Set.of("READY","RUNNING").contains(run.getStatus())||!"SUBMITTING".equals(step.getStatus())))
            throw new ServiceException("PREVIZ_EDIT_LEASE_CHANGED");
    }
    private String project(Long actor,Long run,String manifest){
        if(actor==null||run==null)throw new ServiceException("缺少三维编辑运行身份");
        var invocation=new RuntimeToolInvocation();invocation.setContextManifestJson(manifest);String id=PrevizRuntimeToolProvider.bound(invocation).getString("previzProjectId");projects.requireWrite(actor,id);return id;
    }
    private String operation(RuntimeToolPreparedInvocation p){if(p.getIdempotencyKey()==null||p.getIdempotencyKey().isBlank())throw new ServiceException("缺少编辑幂等标识");return SecureUtil.sha256(p.getIdempotencyKey());}
    private RuntimeToolResult result(JSONObject receipt){if(!"APPLIED".equals(receipt.getString("state")))return failed("PREVIZ_EDIT_NOT_APPLIED","经核对，本次编辑未写入工程，原场景保留");var result=RuntimeToolResult.succeeded(receipt.toJSONString());result.setSafeSummary("对象属性与关键帧已保存到工程");return result;}
    private RuntimeToolResult failed(String code,String summary){var result=new RuntimeToolResult();result.setStatus("FAILED");result.setErrorCode(code);result.setSafeSummary(summary);result.setRetryable(false);return result;}
}
