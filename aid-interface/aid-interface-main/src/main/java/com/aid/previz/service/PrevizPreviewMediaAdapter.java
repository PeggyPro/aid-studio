package com.aid.previz.service;
import com.aid.assistant.service.AssistantExecutionMediaAdapter;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizPreviewMediaAdapter implements AssistantExecutionMediaAdapter {
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeRunStepMapper steps;
    private final PrevizImageReferenceAdapter images;
    private final PrevizProjectService projects;
    @Override public List<MediaTextGenerateRequest.TextContentPart> adapt(Long actor,JSONObject context,Set<String> modalities) {
        JSONObject runInfo=context.getJSONObject("run");
        if(runInfo==null||!"PREVIZ_3DBOX".equals(runInfo.getString("moduleCode")))return List.of();
        Long runId=runInfo.getLong("runId");var run=runId==null?null:runs.selectById(runId);
        if(run==null||!Objects.equals(actor,run.getUserId())||!"0".equals(run.getDelFlag()))throw new ServiceException("预览运行身份无效");
        var refs=context.getJSONObject("executionContext").getJSONObject("manifest").getJSONArray("refs");
        String project=null;
        for(Object raw:refs)if(raw instanceof JSONObject ref && "PREVIZ_PROJECT".equals(ref.getString("kind"))) {
            if(project!=null)throw new ServiceException("预览项目不唯一");project=ref.getJSONObject("canonicalRef").getString("previzProjectId");
        }
        projects.requireWrite(actor,project);
        var successful=steps.selectList(Wrappers.<AidRuntimeRunStep>lambdaQuery().select(AidRuntimeRunStep::getId,AidRuntimeRunStep::getOutputJson)
          .eq(AidRuntimeRunStep::getRunId,runId).in(AidRuntimeRunStep::getToolCode,PrevizRuntimeToolProvider.PREVIEW,PrevizRuntimeToolProvider.RENDER_PREVIEW)
          .eq(AidRuntimeRunStep::getStatus,"SUCCEEDED").eq(AidRuntimeRunStep::getDelFlag,"0").orderByDesc(AidRuntimeRunStep::getId).last("LIMIT 4"));
        Collections.reverse(successful);var parts=new ArrayList<MediaTextGenerateRequest.TextContentPart>();
        for(var step:successful){
            JSONObject receipt=JSON.parseObject(step.getOutputJson());
            if(receipt==null||!project.equals(receipt.getString("projectId")))throw new ServiceException("预览回执项目不一致");
            String image=receipt.getString("imageId");
            JSONObject reference=JSONObject.of("previzProjectId",project,"objectId",image,"contentHash",image);
            parts.addAll(images.adapt(actor,reference,modalities));
        }
        return List.copyOf(parts);
    }
}
