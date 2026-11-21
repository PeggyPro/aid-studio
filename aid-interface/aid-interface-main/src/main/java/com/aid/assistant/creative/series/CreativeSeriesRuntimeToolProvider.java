package com.aid.assistant.creative.series;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.creative.series.dto.SeriesCatalogRequests;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolAvailability;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Project-bound catalog Tool. The main Agent chooses when to call it. */
@Component
public class CreativeSeriesRuntimeToolProvider implements RuntimeToolProvider {
    public static final String SERIES_CATALOG = "creative.series.catalog";
    public static final String VERSION = "1.0.0";
    private static final int MAX_OUTPUT_BYTES = 64_000;
    private static final String INPUT = """
            {"type":"object","properties":{
              "afterEpisodeNo":{"type":["integer","null"],"minimum":0},
              "limit":{"type":["integer","null"],"minimum":1,"maximum":100}
            },"additionalProperties":false}
            """;
    private static final String OUTPUT = """
            {"type":"object","required":["schemaVersion","projectId","projectType","episodeSetDigest","items","complete"],"properties":{
              "schemaVersion":{"const":"aid-creative-series-catalog/v1"},
              "projectId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "projectType":{"const":"series"},
              "episodeSetDigest":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "items":{"type":"array","maxItems":100,"items":{"type":"object","required":["episodeId","episodeNo","currentTargetRevision","effectiveCharCount"],"properties":{
                "episodeId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
                "episodeNo":{"type":"integer","minimum":1},
                "title":{"type":["string","null"],"maxLength":200},
                "currentScriptId":{"type":["string","null"],"pattern":"^[1-9][0-9]{0,18}$"},
                "currentScriptVersion":{"type":["integer","null"],"minimum":1},
                "currentScriptContentHash":{"type":["string","null"],"pattern":"^[0-9a-f]{64}$"},
                "currentTargetRevision":{"type":"string","minLength":1,"maxLength":128},
                "effectiveCharCount":{"type":"integer","minimum":0,"maximum":10000}
              },"additionalProperties":false}},
              "nextAfterEpisodeNo":{"type":["integer","null"],"minimum":1},
              "complete":{"type":"boolean"}
            },"additionalProperties":false}
            """;

    @Resource private CreativeSeriesCatalogService catalog;

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(new RuntimeToolRegistry.Descriptor(SERIES_CATALOG, VERSION,
                SecureUtil.sha256(INPUT + "\n" + OUTPUT), "Read series catalog",
                "Read a body-free catalog of exact episode and script baselines in the current project scope.",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE", List.of("TEXT")));
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor) {
        if (context == null || !"SCREENWRITER".equals(context.getModuleCode())
                || context.getProjectId() == null || !isProjectScopeManifest(context)) {
            return RuntimeToolAvailability.unavailable("SERIES_PROJECT_SCOPE_REQUIRED");
        }
        return RuntimeToolAvailability.enabled();
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        if (!SERIES_CATALOG.equals(invocation.getToolCode())) {
            throw new IllegalArgumentException("Unsupported creative series Tool");
        }
        SeriesCatalogRequests.Read request = JSON.parseObject(invocation.getArgumentsJson(),
                SeriesCatalogRequests.Read.class);
        String output = JSON.toJSONString(catalog.read(invocation, request), JSONWriter.Feature.WriteNulls);
        if (output.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw new IllegalStateException("Creative series catalog output exceeds its byte budget");
        }
        return RuntimeToolResult.succeeded(output);
    }

    private boolean isProjectScopeManifest(RuntimeToolAvailabilityContext context) {
        try {
            JSONObject manifest = JSON.parseObject(context.getContextManifestJson());
            JSONObject scope = manifest == null ? null : manifest.getJSONObject("sessionScope");
            return manifest != null && "assistant-context-manifest/v1".equals(manifest.getString("schemaVersion"))
                    && scope != null
                    && Objects.equals(String.valueOf(context.getProjectId()), scope.getString("projectId"))
                    && "0".equals(scope.getString("episodeId"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
