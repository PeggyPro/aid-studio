package com.aid.runtime.assistant.execution;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.*;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeCommandMapper;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import java.util.*;

/** Bounded immutable operation quote history. No prefix alone ever grants authority. */
@Component
public class AssistantOperationQuoteLineage {
    static final String FIELD="operationQuoteSupersession";
    static final String SCHEMA="assistant-operation-quote-supersession/v1";
    static final int MAX=200;
    private static final Set<String> FIELDS=Set.of("schemaVersion","expectedSourceRunRevision",
            "previousApprovalId","previousApprovalRevision","previousApprovalStatus","previousScopeDigest",
            "rootApprovalId","rootClientRequestId","quoteCommandKey","quoteCommandPayloadDigest",
            "childStepId","requestDigest","historyApprovalIds");
    @Resource private AidRuntimeApprovalMapper approvals;
    @Resource private AidRuntimeCommandMapper commands;
    @Resource private AidBillingAuthorizationReservationMapper reservations;

    static String payloadDigest(AssistantPlanExecutionQuoteCommand command) {
        JSONObject body=new JSONObject();body.put("commandType","OPERATION_QUOTE");
        body.put("runId",String.valueOf(command.runId()));body.put("approvalId",String.valueOf(command.approvalId()));
        body.put("expectedRunRevision",command.expectedRunRevision());body.put("expectedApprovalRevision",command.expectedApprovalRevision());
        if(command.referenceRevalidationToken()!=null)body.put("referenceRevalidationToken",command.referenceRevalidationToken());
        return SecureUtil.sha256(AssistantPlanExecutionJson.canonical(body));
    }
    static String replacementKey(AssistantPlanExecutionQuoteCommand command,Long sessionId) {
        return "oq:"+SecureUtil.sha256(command.userId()+"\n"+sessionId+"\n"+command.clientRequestId()+"\n"
                +command.runId()+"\n"+command.approvalId()+"\n"+command.expectedApprovalRevision()).substring(0,61);
    }
    static void requireShape(JSONObject block) {
        var keys=new HashSet<>(FIELDS);if(block!=null && block.containsKey("referenceRevalidationToken"))keys.add("referenceRevalidationToken");
        check(block!=null && keys.equals(block.keySet()) && SCHEMA.equals(block.get("schemaVersion")));
        if(block.containsKey("referenceRevalidationToken"))check(block.get("referenceRevalidationToken") instanceof String token
                && token.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"));
        for(String key:List.of("expectedSourceRunRevision","previousApprovalId","previousApprovalRevision","rootApprovalId","childStepId"))
            check(integral(block.get(key)) && block.getLong(key)>=0);
        for(String key:List.of("previousScopeDigest","quoteCommandPayloadDigest","requestDigest"))
            check(block.get(key) instanceof String value && value.matches("[0-9a-f]{64}"));
        check(block.get("quoteCommandKey") instanceof String value && value.matches("[A-Za-z0-9._:-]{1,64}"));
        check(block.get("rootClientRequestId") instanceof String value && value.matches("op:[0-9a-f]{61}"));
        check(block.get("previousApprovalStatus") instanceof String status && Set.of("QUOTED","INVALIDATED").contains(status));
        for(String key:List.of("previousApprovalId","rootApprovalId","childStepId"))check(block.getLong(key)>0);
        var history=block.getJSONArray("historyApprovalIds");check(history!=null && !history.isEmpty() && history.size()<=MAX);
        var unique=new HashSet<Long>();for(Object id:history)check(integral(id) && ((Number)id).longValue()>0 && unique.add(((Number)id).longValue()));
        check(Objects.equals(history.getLong(0),block.getLong("rootApprovalId"))
                && Objects.equals(history.getLong(history.size()-1),block.getLong("previousApprovalId")));
    }
    static JSONObject block(AidRuntimeApproval approval) {
        if(approval==null || !"PLAN_EXECUTION".equals(approval.getScopeKind()))return null;
        JSONObject root=JSON.parseObject(approval.getScopeJson());check(root!=null);JSONObject block=root.getJSONObject(FIELD);
        if(block!=null)requireShape(block);return block;
    }

    public void requireApprovalKey(AidRuntimeRun run,AidRuntimeRunStep child,AidRuntimeApproval approval,
                                   String invocationId,boolean lock) {
        String original="op:"+SecureUtil.sha256(invocationId).substring(0,61);
        if(block(approval)==null) {check(Objects.equals(approval.getClientRequestId(),original));return;}
        var all=loadApprovals(run,approval,lock);
        audit(run,child,approval,original,all,loadQuoteReceipts(run,all,lock),loadBudgetHistoryForApprovals(run,all,lock));
    }

    /** Call from fullGraph before its terminal whitelist; current execution retains original guards. */
    public Set<Long> requireSupersededApprovalHistory(AidRuntimeRun run,List<AidRuntimeRunStep> graph,
                                                     Map<Long,AidRuntimeApproval> all) {
        return requireSupersededApprovalHistory(run,graph,all,true);
    }
    public Set<Long> requireSupersededApprovalHistory(AidRuntimeRun run,List<AidRuntimeRunStep> graph,
                                                     Map<Long,AidRuntimeApproval> all,boolean lock) {
        boolean present=graph.stream().filter(step->step.getApprovalId()!=null).anyMatch(step->{
            AidRuntimeApproval approval=all.get(step.getApprovalId());
            return approval!=null && "PLAN_EXECUTION".equals(approval.getScopeKind())
                    && approval.getClientRequestId()!=null && approval.getClientRequestId().startsWith("oq:");
        });
        if(!present)return Set.of();
        var relevant=new HashMap<Long,AidRuntimeApproval>();
        for(var step:graph) {
            var approval=all.get(step.getApprovalId());if(approval==null || block(approval)==null)continue;
            relevant.put(approval.getId(),approval);
            for(Object raw:block(approval).getJSONArray("historyApprovalIds")) {
                Long id=((Number)raw).longValue();check(all.containsKey(id));relevant.put(id,all.get(id));
            }
        }
        List<AidRuntimeCommand> quotes=loadQuoteReceipts(run,relevant,lock);
        List<AidRuntimeCommand> budget=loadBudgetHistoryForApprovals(run,relevant,lock);
        Set<Long> safe=new HashSet<>();
        for(AidRuntimeRunStep child:graph) {
            AidRuntimeApproval current=all.get(child.getApprovalId());
            if(current==null || block(current)==null)continue;
            String invocation=JSON.parseObject(current.getScopeJson()).getJSONObject("decision").getString("invocationId");
            safe.addAll(audit(run,child,current,"op:"+SecureUtil.sha256(invocation).substring(0,61),all,quotes,budget));
        }
        if(!safe.isEmpty())check(reservations.selectList(new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                .select(AidBillingAuthorizationReservation::getId).in(AidBillingAuthorizationReservation::getApprovalId,safe)
                .last(lock?"LIMIT 1 FOR UPDATE":"LIMIT 1")).isEmpty());
        return Set.copyOf(safe);
    }

    private Set<Long> audit(AidRuntimeRun run,AidRuntimeRunStep child,AidRuntimeApproval current,String original,
            Map<Long,AidRuntimeApproval> all,List<AidRuntimeCommand> quoteCommands,List<AidRuntimeCommand> budgetCommands) {
        Map<String,AidRuntimeCommand> receipts=new HashMap<>();
        for(AidRuntimeCommand command:quoteCommands) {
            check(command!=null && Objects.equals(command.getActorId(),run.getUserId())
                    && Objects.equals(command.getSessionId(),run.getSessionId()) && "0".equals(command.getDelFlag())
                    && "OPERATION_QUOTE".equals(command.getCommandType())
                    && ("COMPLETED".equals(command.getStatus()) || "FAILED".equals(command.getStatus())
                        && "OPERATION_QUOTE_REFERENCE_REVALIDATION_REQUIRED".equals(command.getErrorCode()))
                    && receipts.putIfAbsent(command.getClientRequestId(),command)==null);
        }
        Set<Long> ancestors=new HashSet<>();Set<Long> visited=new HashSet<>();
        JSONObject first=block(current);check(first!=null);
        Long rootId=first.getLong("rootApprovalId");String rootKey=first.getString("rootClientRequestId");
        check(Objects.equals(rootKey,original));
        AidRuntimeApproval next=current;
        for(int depth=0;depth<=MAX;depth++) {
            check(next!=null && next.getId()!=null && visited.add(next.getId())
                    && Objects.equals(next.getRunId(),run.getId()) && Objects.equals(next.getUserId(),run.getUserId())
                    && "0".equals(next.getDelFlag()) && "PLAN_EXECUTION".equals(next.getScopeKind()));
            JSONObject link=block(next);
            if(link==null) {
                check(Objects.equals(next.getId(),rootId) && Objects.equals(next.getClientRequestId(),original));
                check(ancestors.size()==first.getJSONArray("historyApprovalIds").size()
                        && ancestors.equals(new HashSet<>(first.getJSONArray("historyApprovalIds").toJavaList(Long.class))));
                return ancestors;
            }
            check(depth<MAX);
            check(Objects.equals(link.getLong("rootApprovalId"),rootId) && Objects.equals(link.getString("rootClientRequestId"),rootKey)
                    && Objects.equals(link.getLong("childStepId"),child.getId())
                    && Objects.equals(link.getString("requestDigest"),next.getRequestDigest()));
            AidRuntimeApproval previous=all.get(link.getLong("previousApprovalId"));
            check(previous!=null && !Objects.equals(previous.getId(),next.getId())
                    && "INVALIDATED".equals(previous.getStatus()) && previous.getInvalidatedAt()!=null
                    && previous.getAcceptedAt()==null && Objects.equals(previous.getRunId(),run.getId())
                    && Objects.equals(previous.getUserId(),run.getUserId()) && "0".equals(previous.getDelFlag())
                    && Objects.equals(previous.getScopeDigest(),link.getString("previousScopeDigest"))
                    && Objects.equals(previous.getStateRevision(),link.getLong("previousApprovalRevision")
                       +("QUOTED".equals(link.getString("previousApprovalStatus"))?1:0)));
            var previousLink=block(previous);var expectedHistory=previousLink==null?new ArrayList<Long>():new ArrayList<>(previousLink.getJSONArray("historyApprovalIds").toJavaList(Long.class));
            expectedHistory.add(previous.getId());check(expectedHistory.equals(link.getJSONArray("historyApprovalIds").toJavaList(Long.class)));
            AidRuntimeCommand receipt=receipts.get(link.getString("quoteCommandKey"));
            AssistantPlanExecutionQuoteCommand request=new AssistantPlanExecutionQuoteCommand(run.getUserId(),run.getId(),previous.getId(),
                    link.getLong("expectedSourceRunRevision"),link.getLong("previousApprovalRevision"),link.getString("quoteCommandKey"),link.getString("referenceRevalidationToken"));
            check(receipt!=null && "COMPLETED".equals(receipt.getStatus()) && Objects.equals(receipt.getPayloadDigest(),payloadDigest(request))
                    && Objects.equals(link.getString("quoteCommandPayloadDigest"),receipt.getPayloadDigest())
                    && Objects.equals(next.getClientRequestId(),replacementKey(request,run.getSessionId())));
            AssistantPlanExecutionQuoted result=JSON.parseObject(receipt.getResultJson(),AssistantPlanExecutionQuoted.class);
            check(result!=null && !result.executionSubmitted() && "REPLACED".equals(result.quoteDisposition())
                    && "PLAN_EXECUTION".equals(result.scopeKind()) && "QUOTED".equals(result.status())
                    && Objects.equals(result.runId(),String.valueOf(run.getId()))
                    && Objects.equals(result.stepId(),String.valueOf(child.getId()))
                    && Objects.equals(result.approvalId(),String.valueOf(next.getId()))
                    && Objects.equals(result.previousApprovalId(),String.valueOf(previous.getId()))
                    && result.runRevision()==request.expectedRunRevision()+1 && result.approvalRevision()==0
                    && result.planRevision()==child.getPlanRevision() && "CONFIRM_OPERATION_APPROVAL".equals(result.nextAction())
                    && result.quote()!=null && result.quote().isHardUpperBoundAvailable()
                    && Objects.equals(result.quote().getQuoteId(),next.getQuoteId())
                    && next.getMaxCharge()!=null && Objects.equals(result.quote().getQuotedUpperBound(),next.getMaxCharge().setScale(4).toPlainString())
                    && next.getExpiresAt()!=null && Objects.equals(result.quote().getExpiresAt(),next.getExpiresAt().toInstant().toString())
                    && "BALANCE".equals(result.quote().getCurrencyUnit()) && result.quote().getPrecisionScale()==4);
            if(request.referenceRevalidationToken()!=null) {
                var challengeReceipt=receipts.get(request.referenceRevalidationToken());
                check(challengeReceipt!=null && "FAILED".equals(challengeReceipt.getStatus())
                        && "OPERATION_QUOTE_REFERENCE_REVALIDATION_REQUIRED".equals(challengeReceipt.getErrorCode()));
                JSONObject stored=JSON.parseObject(challengeReceipt.getResultJson());JSONObject challenge=stored==null?null:stored.getJSONObject("challenge");
                var challengeRequest=new AssistantPlanExecutionQuoteCommand(run.getUserId(),run.getId(),previous.getId(),
                        request.expectedRunRevision(),request.expectedApprovalRevision(),request.referenceRevalidationToken());
                check(challenge!=null && "assistant-operation-reference-challenge/v1".equals(stored.getString("schemaVersion"))
                        && Objects.equals(challengeReceipt.getPayloadDigest(),payloadDigest(challengeRequest))
                        && Objects.equals(stored.getString("requestPayloadDigest"),challengeReceipt.getPayloadDigest())
                        && Objects.equals(stored.getString("sourceScopeDigest"),previous.getScopeDigest())
                        && Objects.equals(stored.getString("sourceRequestDigest"),previous.getRequestDigest())
                        && Objects.equals(challenge.getString("runId"),String.valueOf(run.getId()))
                        && Objects.equals(challenge.getLong("runRevision"),request.expectedRunRevision())
                        && Objects.equals(challenge.getString("approvalId"),String.valueOf(previous.getId()))
                        && Objects.equals(challenge.getLong("approvalRevision"),request.expectedApprovalRevision())
                        && Boolean.FALSE.equals(challenge.getBoolean("executionSubmitted"))
                        && receipt.getCreateTime()!=null && !receipt.getCreateTime().toInstant().isAfter(java.time.Instant.parse(challenge.getString("expiresAt")))
                        && result.referenceRevalidated() && result.referenceResources()!=null
                        && Objects.equals(AssistantPlanExecutionJson.canonicalExplicitNulls(result.referenceResources()),AssistantPlanExecutionJson.canonicalExplicitNulls(challenge.getJSONArray("resources")))
                        && Objects.equals(AssistantPlanExecutionJson.canonicalExplicitNulls(stored.getJSONObject("evidence")),AssistantPlanExecutionJson.canonicalExplicitNulls(JSON.parseObject(new AssistantPlanExecutionScopeCodec().decode(next.getScopeJson()).preparedPayloadJson()).getJSONObject("serverResourceEvidence"))));
            } else check(!result.referenceRevalidated() && result.referenceResources()!=null && result.referenceResources().isEmpty());
            requireStableSource(run,previous,next,budgetCommands);
            ancestors.add(previous.getId());next=previous;
        }
        throw new ServiceException("OPERATION_QUOTE_LINEAGE_TOO_LARGE");
    }

    private void requireStableSource(AidRuntimeRun run,AidRuntimeApproval previous,AidRuntimeApproval next,List<AidRuntimeCommand> budget) {
        JSONObject old=JSON.parseObject(previous.getScopeJson()),fresh=JSON.parseObject(next.getScopeJson());
        var oldParsed=new AssistantPlanExecutionScopeCodec().decode(previous.getScopeJson());
        var newParsed=new AssistantPlanExecutionScopeCodec().decode(next.getScopeJson());
        check(Objects.equals(oldParsed.digest(),previous.getScopeDigest())
                && Objects.equals(newParsed.digest(),next.getScopeDigest()));
        Set<String> policyFields=Set.of("authorizationId","authorizationRevision","revision","mode","scopeDigest");
        check(policyFields.equals(old.getJSONObject("policy").keySet())
                && policyFields.equals(fresh.getJSONObject("policy").keySet())
                && Objects.equals(old.getJSONObject("policy").getLong("authorizationId"),previous.getBillingAuthorizationId())
                && Objects.equals(old.getJSONObject("policy").getLong("authorizationRevision"),previous.getBillingAuthorizationRevision())
                && Objects.equals(fresh.getJSONObject("policy").getLong("authorizationId"),next.getBillingAuthorizationId())
                && Objects.equals(fresh.getJSONObject("policy").getLong("authorizationRevision"),next.getBillingAuthorizationRevision()));
        check(Objects.equals(previous.getRequestDigest(),next.getRequestDigest())
                && Objects.equals(previous.getAnswerDigest(),next.getAnswerDigest())
                && Objects.equals(previous.getPlanRevision(),next.getPlanRevision())
                && Objects.equals(previous.getPlanDigest(),next.getPlanDigest())
                && Objects.equals(previous.getContextDigest(),next.getContextDigest()));
        if(!Objects.equals(previous.getBillingAuthorizationRevision(),next.getBillingAuthorizationRevision()))
            AssistantOperationQuoteGrantHistory.requireBudgetRepriceSource(run,previous,next.getBillingAuthorizationId(),
                    next.getBillingAuthorizationRevision(),fresh.getJSONObject("policy").getString("scopeDigest"),budget);
        else check(Objects.equals(previous.getBillingAuthorizationId(),next.getBillingAuthorizationId())
                && Objects.equals(old.getJSONObject("policy").get("scopeDigest"),fresh.getJSONObject("policy").get("scopeDigest"))
                && Objects.equals(old.getJSONObject("policy").get("mode"),fresh.getJSONObject("policy").get("mode")));
        for(JSONObject root:List.of(old,fresh)) {
            root.remove(FIELD);root.remove("policy");root.getJSONObject("operationScope").remove("quotedUpperBound");
            JSONObject intent=root.getJSONObject("tool").getJSONObject("billingIntent");
            for(String key:List.of("quoteId","quoteDigest","quoteExpiresAt","quotedUpperBound"))intent.remove(key);
            JSONObject payload=JSON.parseObject(root.getJSONObject("tool").getString("preparedPayloadJson"));
            payload.remove("quoteId");payload.remove("quoteDigest");
            if(block(next).containsKey("referenceRevalidationToken"))payload.remove("serverResourceEvidence");
            root.getJSONObject("tool").put("preparedPayloadJson",AssistantPlanExecutionJson.canonicalExplicitNulls(payload));
            root.getJSONObject("tool").remove("preparedPayloadDigest");
        }
        check(Objects.equals(AssistantPlanExecutionJson.canonicalExplicitNulls(old),AssistantPlanExecutionJson.canonicalExplicitNulls(fresh)));
    }
    private Map<Long,AidRuntimeApproval> loadApprovals(AidRuntimeRun run,AidRuntimeApproval current,boolean lock) {
        var ids=new HashSet<>(block(current).getJSONArray("historyApprovalIds").toJavaList(Long.class));check(ids.add(current.getId()));
        var list=approvals.selectList(new LambdaQueryWrapper<AidRuntimeApproval>().in(AidRuntimeApproval::getId,ids)
                .last(lock?"LIMIT 202 FOR UPDATE":"LIMIT 202"));check(list.size()==ids.size());
        Map<Long,AidRuntimeApproval> result=new HashMap<>();for(var item:list)check(item!=null && item.getId()!=null && result.putIfAbsent(item.getId(),item)==null);
        check(result.keySet().equals(ids));
        return result;
    }
    private List<AidRuntimeCommand> loadQuoteReceipts(AidRuntimeRun run,Map<Long,AidRuntimeApproval> all,boolean lock) {
        var keys=new HashSet<String>();for(var approval:all.values()) {
            var link=block(approval);if(link==null)continue;keys.add(link.getString("quoteCommandKey"));
            if(link.containsKey("referenceRevalidationToken"))keys.add(link.getString("referenceRevalidationToken"));
        }
        check(keys.size()<=MAX*2);if(keys.isEmpty())return List.of();
        var list=commands.selectList(new LambdaQueryWrapper<AidRuntimeCommand>().eq(AidRuntimeCommand::getSessionId,run.getSessionId())
                .eq(AidRuntimeCommand::getActorId,run.getUserId()).eq(AidRuntimeCommand::getCommandType,"OPERATION_QUOTE")
                .in(AidRuntimeCommand::getClientRequestId,keys).last(lock?"LIMIT 401 FOR UPDATE":"LIMIT 401"));
        check(list.size()==keys.size());return list;
    }
    private List<AidRuntimeCommand> loadBudgetHistoryForApprovals(AidRuntimeRun run,Map<Long,AidRuntimeApproval> all,boolean lock) {
        if(all.isEmpty())return List.of();var auths=new HashSet<Long>();long min=Long.MAX_VALUE,max=-1;
        for(var approval:all.values()) {check(approval.getBillingAuthorizationId()!=null && approval.getBillingAuthorizationRevision()!=null);
            auths.add(approval.getBillingAuthorizationId());min=Math.min(min,approval.getBillingAuthorizationRevision());max=Math.max(max,approval.getBillingAuthorizationRevision());}
        check(auths.size()==1);return loadBudgetGrantHistory(run,auths.iterator().next(),min,max,lock);
    }
    public List<AidRuntimeCommand> loadBudgetGrantHistory(AidRuntimeRun run,Long currentAuthId,long oldRevision,long currentRevision,boolean lock) {
        check(run!=null && currentAuthId!=null && oldRevision>=0 && currentRevision>=oldRevision && currentRevision-oldRevision<=MAX);
        if(currentRevision==oldRevision)return List.of();
        String safe="CASE WHEN JSON_VALID(result_json) THEN result_json ELSE '{}' END";
        var query=new LambdaQueryWrapper<AidRuntimeCommand>().eq(AidRuntimeCommand::getSessionId,run.getSessionId())
                .eq(AidRuntimeCommand::getActorId,run.getUserId()).eq(AidRuntimeCommand::getCommandType,"BUDGET_REVISE")
                .eq(AidRuntimeCommand::getStatus,"COMPLETED")
                .apply("JSON_UNQUOTE(JSON_EXTRACT("+safe+",'$.budgetPolicy.runId')) = {0}",String.valueOf(run.getId()))
                .apply("JSON_UNQUOTE(JSON_EXTRACT("+safe+",'$.budgetPolicy.authorizationId')) = {0}",String.valueOf(currentAuthId))
                .apply("CAST(JSON_UNQUOTE(JSON_EXTRACT("+safe+",'$.budgetPolicy.authorizationRevision')) AS UNSIGNED) > {0}",oldRevision)
                .apply("CAST(JSON_UNQUOTE(JSON_EXTRACT("+safe+",'$.budgetPolicy.authorizationRevision')) AS UNSIGNED) <= {0}",currentRevision)
                .orderByAsc(AidRuntimeCommand::getId).last(lock?"LIMIT 201 FOR UPDATE":"LIMIT 201");
        var list=commands.selectList(query);check(list.size()<=MAX);return list;
    }
    private static boolean integral(Object value){return value instanceof Long number && number>=0 || value instanceof Integer number2 && number2>=0;}
    private static void check(boolean good){if(!good)throw new ServiceException("OPERATION_QUOTE_LINEAGE_INVALID");}
}
