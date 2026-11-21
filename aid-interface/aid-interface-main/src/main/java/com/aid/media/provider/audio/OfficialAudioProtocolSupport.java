package com.aid.media.provider.audio;

import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 原生音频SPI共用执行器；任务/并发/账务仍由统一媒体服务持有。 */
@Component
@RequiredArgsConstructor
public class OfficialAudioProtocolSupport {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("media-upstream-anomaly");
    private final OfficialAudioContractRegistry contracts;
    private final OfficialAudioTransport transport;
    private final OfficialAudioResponseCodec codec;
    private final OfficialAudioInputService inputs;
    private final OfficialAudioArtifacts artifacts;
    private final OfficialAudioResourceService resources;
    private final OfficialAudioInlineInputs inlineInputs;
    private final com.aid.aid.mapper.AidMediaTaskMapper tasks;
    private final com.aid.aid.mapper.AidMediaResultMapper results;
    private final org.redisson.api.RedissonClient redis;
    private final org.springframework.transaction.PlatformTransactionManager transactions;

    public boolean synchronous(AiModelConfigVo config,MediaAudioGenerateRequest request) {
        var contract=contracts.contract(OfficialAudioContractRegistry.operation(config));
        if(!contract.path("async").asBoolean())return true;
        return contract.path("vendor").asText().equals("sonilo") && java.util.Set.of("/v1/text-to-music","/v1/video-to-music").contains(contract.path("path").asText())
                && (request.getOptions()==null||!"async".equals(request.getOptions().get("mode")));
    }

    public void validate(String vendor,AiModelConfigVo config,MediaAudioGenerateRequest request,boolean metadata) {
        if(request.isPreviewMode())throw new ServiceException("此音频能力需要使用统一生成任务");
        var plan=contracts.prepare(vendor,config,request);
        if(plan.adminOnly()&&!"operator_official_audio".equals(request.getBizTaskType()))throw new ServiceException("此音频资源操作仅允许后台执行");
        resources.validate(config,request,plan);
        transport.validateConfiguration(config, plan);
        if(metadata) {inlineInputs.stage(request);plan=contracts.prepare(vendor,config,request);}
        if(metadata&&java.util.Set.of("mureka.post.song_stem","mureka.post.song_describe").contains(plan.operation())&&plan.body().get("url") instanceof String value&&value.startsWith("data:")) {
            Map<String,Object> options=new java.util.LinkedHashMap<>(request.getOptions());options.put("url",artifacts.stageMurekaDataUrl(value));request.setOptions(options);
            plan=contracts.prepare(vendor,config,request);
        }
        if(metadata && "seed".equals(vendor) && plan.body().get("references") instanceof java.util.List<?> refs) {
            boolean changed=false;
            for(Object value:refs) if(value instanceof Map<?,?> raw) {
                @SuppressWarnings("unchecked") Map<String,Object> ref=(Map<String,Object>)raw;
                for(String kind:java.util.List.of("audio","image")) if(ref.get(kind+"_data") instanceof String encoded) {
                    String url=artifacts.stageReference(encoded,kind.equals("image"));
                    ref.remove(kind+"_data");ref.put(kind+"_url",url);changed=true;
                }
            }
            if(changed) {
                Map<String,Object> options=new java.util.LinkedHashMap<>(request.getOptions());options.put("references",refs);request.setOptions(options);
                plan=contracts.prepare(vendor,config,request);
            }
        }
        var contract=contracts.contract(plan.operation());
        if(contract.path("async").asBoolean())ProviderEndpointUtils.normalizeTaskQueryTemplate(config.getTaskQuerySuffix());
        request.setVerifiedAudioBillingInput(inputs.prepare(config,request,plan,metadata));
    }

    public ProviderSubmitResult submit(String vendor,AiModelConfigVo config,MediaAudioGenerateRequest request) {
        var plan=contracts.prepare(vendor,config,request);
        inlineInputs.materialize(plan.body(),request);
        var response=transport.submit(config,plan,contracts.contract(plan.operation()));
        var result=codec.submit(response,plan);
        resources.record(config,request,plan,result);
        return result;
    }

    public ProviderTaskResult query(String vendor,AiModelConfigVo config,String taskId) {
        org.redisson.api.RLock lock=null;boolean locked=false;
        try{
            var rows=tasks.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aid.aid.domain.media.AidMediaTask>lambdaQuery()
                    .eq(com.aid.aid.domain.media.AidMediaTask::getProviderTaskId,taskId).eq(com.aid.aid.domain.media.AidMediaTask::getModelName,config.getModelCode()).last("LIMIT 2"));
            if(rows.size()!=1)throw new ServiceException("上游音频任务归属无法确认");
            var task=rows.get(0);
            lock=redis.getLock("aid:media:official-audio:query:"+task.getId());locked=lock.tryLock();
            if(!locked)return unavailable();
            var stored=readCompletedManifest(task.getId());if(stored!=null)return stored;
            var result=codec.query(vendor,transport.queryTask(config,taskId),contracts.contract(OfficialAudioContractRegistry.operation(config)).path("resultKind").asText("audio"));
            if("SUCCEEDED".equals(result.getStatus())&&"sonilo.post.video_analysis".equals(OfficialAudioContractRegistry.operation(config))&&task.getBillingSnapshotJson()!=null) {
                var snapshot=com.alibaba.fastjson2.JSON.parseObject(task.getBillingSnapshotJson(),com.aid.billing.model.BillingSnapshot.class);
                if(snapshot.getRequestParams()!=null&&snapshot.getRequestParams().get("inputVideoSeconds") instanceof Number seconds) {
                    Map<String,Object> usage=result.getUsage()==null?new java.util.LinkedHashMap<>():new java.util.LinkedHashMap<>(result.getUsage());
                    int count=snapshot.getRequestParams().get("outputCount") instanceof Number n?n.intValue():1;
                    usage.put("actualDuration",new java.math.BigDecimal(seconds.toString()).multiply(java.math.BigDecimal.valueOf(count)));result.setUsage(usage);
                }
            }
            if("SUCCEEDED".equals(result.getStatus())&&result.getAudioOutputs()!=null&&!result.getAudioOutputs().isEmpty()) {
                var first=result.getAudioOutputs().get(0);
                Map<String,Object> details=first.getMetadata()==null?new java.util.LinkedHashMap<>():new java.util.LinkedHashMap<>(first.getMetadata());
                details.put("officialAudioComplete",true);details.put("officialAudioUsage",result.getUsage());first.setMetadata(details);
                // The upstream terminal result is durable evidence before the task CAS; a concurrent poll reuses the same stored files.
                new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
                    AudioResultManifestSupport.persist(task,result.getAudioOutputs(),results);
                    if("mureka".equals(vendor))resources.recordOutputs(config.getProviderId(),task.getUserId(),result.getAudioOutputs());
                });
            }
            return result;
        }
        catch(Exception ex){
            String reason = ex instanceof ServiceException
                    ? com.aid.media.provider.ProviderErrorSanitizer.safeMessage(ex.getMessage(), "音频任务状态暂不可确认")
                    : "音频任务查询或归档异常";
            LOG.warn("official audio query/archive unavailable, provider={}, model={}, task={}, errorType={}, reason={}",
                    vendor, config.getModelCode(), taskId, ex.getClass().getSimpleName(), reason);
            return unavailable();
        }
        finally{if(locked&&lock!=null&&lock.isHeldByCurrentThread())lock.unlock();}
    }
    private ProviderTaskResult unavailable(){return ProviderTaskResult.builder().status("PROCESSING").querySuccessful(false).terminalConfirmed(false).errorMessage("音频任务状态暂不可确认").build();}
    @SuppressWarnings("unchecked")
    private ProviderTaskResult readCompletedManifest(Long taskId) {
        var rows=results.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aid.aid.domain.media.AidMediaResult>lambdaQuery()
                .eq(com.aid.aid.domain.media.AidMediaResult::getTaskId,taskId).orderByAsc(com.aid.aid.domain.media.AidMediaResult::getResultIndex));
        if(rows.isEmpty())return null;
        java.util.List<com.aid.media.provider.AudioOutputItem> items=new java.util.ArrayList<>();
        for(var row:rows){
            if(row.getOssUrl()==null||row.getMetadataJson()==null)return null;
            var item=com.alibaba.fastjson2.JSON.parseObject(row.getMetadataJson(),com.aid.media.provider.AudioOutputItem.class);
            if(item==null)return null;item.setUrl(row.getOssUrl());items.add(item);
        }
        Map<String,Object> metadata=items.get(0).getMetadata();
        if(metadata==null||!Boolean.TRUE.equals(metadata.get("officialAudioComplete")))return null;
        Map<String,Object> usage=metadata.get("officialAudioUsage") instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();
        return ProviderTaskResult.builder().status("SUCCEEDED").querySuccessful(true).terminalConfirmed(true).providerStatus("succeeded")
                .resultUrl(items.get(0).getUrl()).audioOutputs(items).usage(usage).build();
    }
}
