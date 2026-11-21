package com.aid.previz.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.assistant.execution.AssistantTechnicalArchiveVerifier;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Technical completion means verified image bytes, never an inferred visual-quality pass. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
@RequiredArgsConstructor
public class PrevizPreviewArchiveCompletionVerifier implements AssistantTechnicalArchiveVerifier {
    private final AidRuntimeArtifactChunkMapper chunks;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeRunStepMapper steps;
    private final PrevizReferenceImageStore images;
    private final PrevizArtifactService artifacts;
    @Override public String artifactType(){return "PREVIZ_PREVIEW";}
    @Override public void requireArchived(AidRuntimeArtifact artifact){
        try {
            if(artifact==null||!artifactType().equals(artifact.getArtifactType())||!"FINALIZED".equals(artifact.getStatus())||!"0".equals(artifact.getDelFlag()))invalid();
            var run=runs.selectById(artifact.getProducerRunId());var step=steps.selectById(artifact.getProducerStepId());
            if(run==null||step==null||!Objects.equals(step.getRunId(),run.getId())||!"SUCCEEDED".equals(step.getStatus())
                    ||!PrevizRuntimeToolProvider.RENDER_PREVIEW.equals(step.getToolCode()))invalid();
            var rows=chunks.selectAllActive(artifact.getId());
            if(rows.size()!=1||rows.get(0).getChunkIndex()!=0||rows.get(0).getCharStart()!=0)invalid();
            String text=rows.get(0).getContentText();
            if(text==null||text.length()>65536||!SecureUtil.sha256(text).equals(artifact.getContentHash())
                    ||!Objects.equals(rows.get(0).getContentHash(),artifact.getContentHash()))invalid();
            var receipt=JSON.parseObject(text);var stored=JSON.parseObject(step.getOutputJson());
            if(stored==null||!Objects.equals(receipt.getString("imageId"),stored.getString("imageId"))
                    ||!Objects.equals(receipt.getString("projectId"),stored.getString("projectId"))
                    ||!Objects.equals(receipt.getString("buildStepId"),stored.getString("buildStepId")))invalid();
            String project=receipt.getString("projectId");
            artifacts.manifest(run.getUserId(),project,receipt.getLong("buildStepId"));
            var image=images.read(run.getUserId(),project,receipt.getString("imageId"));
            if(!SecureUtil.sha256(new java.io.ByteArrayInputStream(image.bytes())).equals(receipt.getString("sha256")))invalid();
        }catch(java.io.IOException|RuntimeException error){throw new ServiceException("COMPLETION_TECHNICAL_EVIDENCE_INVALID");}
    }
    private static void invalid(){throw new ServiceException("COMPLETION_TECHNICAL_EVIDENCE_INVALID");}
}
