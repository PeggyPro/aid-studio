package com.aid.previz.service;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.common.exception.ServiceException;
import com.aid.previz.workspace.PrevizSourceStore;
import com.aid.runtime.assistant.execution.AssistantTargetWriteCompletionVerifier;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Exact persisted source receipt, current project permission and immutable file bytes. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "aid.previz.runtime", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PrevizSourceWriteCompletionVerifier implements AssistantTargetWriteCompletionVerifier {
    private final PrevizProjectService projects;
    private final PrevizSourceStore sources;

    @Override public Set<String> supportedToolCodes() { return Set.of(PrevizRuntimeToolProvider.WRITE,PrevizRuntimeToolProvider.EDIT); }

    @Override public void requireCurrentWrite(VerifiedRuntimeToolEffect effect) {
        verify(effect, true);
    }

    public void requirePersistedWrite(VerifiedRuntimeToolEffect effect) {
        verify(effect, false);
    }

    /** Intermediate immutable source versions may be superseded within one verified plan.
     * Every declared version must exist and match its bytes, and each project's current head
     * must still be represented by this same plan's verified completion receipts. */
    @Override public void requireCompletedWrites(java.util.List<VerifiedRuntimeToolEffect> effects) {
        if(effects==null||effects.isEmpty()||effects.size()>100)invalid();
        var first=effects.get(0);var ids=new java.util.HashSet<Long>();
        var versions=new java.util.HashMap<String,java.util.Set<String>>();
        for(var effect:effects){
            verify(effect,false);
            if(!Objects.equals(first.userId(),effect.userId())||!Objects.equals(first.sessionId(),effect.sessionId())
                    ||!Objects.equals(first.runId(),effect.runId())||!ids.add(effect.stepId()))invalid();
            var output=JSON.parseObject(effect.outputJson());
            versions.computeIfAbsent(output.getString("projectId"),key->new java.util.HashSet<>()).add(output.getString("sourceVersion"));
        }
        try{
            for(var entry:versions.entrySet())if(!entry.getValue().contains(sources.currentVersion(first.userId(),entry.getKey())))invalid();
        }catch(java.io.IOException unavailable){invalid();}
    }

    private void verify(VerifiedRuntimeToolEffect effect, boolean requireCurrent) {
        if (effect == null || effect.userId() == null || effect.runId() == null || effect.stepId() == null
                || !supportedToolCodes().contains(effect.toolCode())
                || !"1.0.0".equals(effect.contractVersion()) || effect.schemaDigest() == null
                || !effect.schemaDigest().matches("[a-f0-9]{64}") || effect.idempotencyKey() == null
                || effect.idempotencyKey().isBlank() || effect.outputArtifactRefs() == null
                || !effect.outputArtifactRefs().isEmpty()) invalid();
        try {
            JSONObject output = JSON.parseObject(effect.outputJson());
            JSONObject input = JSON.parseObject(effect.preparedPayloadJson() == null
                    ? effect.argumentsJson() : effect.preparedPayloadJson());
            JSONObject manifest = JSON.parseObject(effect.contextManifestJson());
            var refs = manifest == null ? null : manifest.getJSONArray("refs");
            String projectId = null;
            if (refs != null) for (Object raw : refs) {
                if (!(raw instanceof JSONObject ref) || !"PREVIZ_PROJECT".equals(ref.getString("kind"))) continue;
                if (projectId != null || !"WRITE".equals(ref.getString("access"))) invalid();
                projectId = ref.getJSONObject("canonicalRef").getString("previzProjectId");
            }
            if (projectId == null || output == null || !projectId.equals(output.getString("projectId"))
                    || input == null || PrevizRuntimeToolProvider.WRITE.equals(effect.toolCode()) && input.getJSONObject("files") == null) invalid();
            projects.requireWrite(effect.userId(), projectId);
            String version = output.getString("sourceVersion");
            if (version == null || !version.matches("[a-f0-9]{64}")
                    || requireCurrent && !Objects.equals(version, sources.currentVersion(effect.userId(), projectId))) invalid();
            var actual = sources.read(effect.userId(), projectId, version);
            java.util.Map<String,byte[]> expected = new java.util.TreeMap<>();
            if(PrevizRuntimeToolProvider.EDIT.equals(effect.toolCode())) {
                var edited=com.aid.previz.workspace.PrevizSourceEdits.prepare(sources,effect.userId(),projectId,input);
                expected.putAll(edited.files());
                if(!Objects.equals(output.getString("path"),input.getString("path")))invalid();
            } else for(var item:input.getJSONObject("files").entrySet()) {
                if(!(item.getValue() instanceof String text))invalid();
                expected.put(item.getKey(),((String)item.getValue()).getBytes(StandardCharsets.UTF_8));
            }
            if (actual.size() != expected.size()) invalid();
            for (var item : expected.entrySet()) {
                if (!java.util.Arrays.equals(actual.get(item.getKey()), item.getValue())) invalid();
            }
        } catch (java.io.IOException | RuntimeException error) {
            throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID");
        }
    }

    private static void invalid() { throw new ServiceException("COMPLETION_TARGET_EVIDENCE_INVALID"); }
}
