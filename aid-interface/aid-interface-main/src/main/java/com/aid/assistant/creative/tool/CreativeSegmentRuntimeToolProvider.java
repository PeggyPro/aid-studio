package com.aid.assistant.creative.tool;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.creative.dto.CreativeSegmentRequests;
import com.aid.assistant.creative.service.CreativeArtifactSegmentReadService;
import com.aid.assistant.creative.service.CreativeSourceSegmentReadService;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Versioned exact-text segment readers for bounded Assistant observations. */
@Component
public class CreativeSegmentRuntimeToolProvider implements RuntimeToolProvider {
    public static final String SOURCE_SEGMENT_READ = "creative.source.segment.read";
    public static final String ARTIFACT_SEGMENT_READ = "creative.artifact.segment.read";
    public static final String VERSION = "1.0.0";
    private static final int MAX_OUTPUT_BYTES = 64_000;

    private static final String CURSOR = """
            {"type":"object","required":["chunkIndex","charOffset"],"properties":{
              "chunkIndex":{"type":"integer","minimum":0},
              "charOffset":{"type":"integer","minimum":0}
            },"additionalProperties":false}
            """;
    private static final String SEGMENT = """
            {"type":"object","required":["parentChunkIndex","parentCharStart","parentCharEnd","parentCharCount","parentContentHash","charStart","charEnd","charCount","utf8ByteCount","contentHash","content"],"properties":{
              "parentChunkIndex":{"type":"integer","minimum":0},
              "parentCharStart":{"type":"integer","minimum":0},
              "parentCharEnd":{"type":"integer","minimum":0},
              "parentCharCount":{"type":"integer","minimum":1,"maximum":50000},
              "parentContentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "charStart":{"type":"integer","minimum":0},
              "charEnd":{"type":"integer","minimum":1},
              "charCount":{"type":"integer","minimum":1,"maximum":8192},
              "utf8ByteCount":{"type":"integer","minimum":1,"maximum":8192},
              "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "content":{"type":"string","minLength":1,"maxLength":8192}
            },"additionalProperties":false}
            """;
    private static final String COMMON_INPUT = """
              "chunkIndex":{"type":["integer","null"],"minimum":0},
              "charOffset":{"type":["integer","null"],"minimum":0},
              "maxUtf8Bytes":{"type":["integer","null"],"minimum":256,"maximum":8192}
            """;
    private static final String SOURCE_INPUT = """
            {"type":"object","required":["sourceRevisionId"],"properties":{
              "sourceRevisionId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              %s
            },"additionalProperties":false}
            """.formatted(COMMON_INPUT);
    private static final String ARTIFACT_INPUT = """
            {"type":"object","required":["artifactId"],"properties":{
              "artifactId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              %s
            },"additionalProperties":false}
            """.formatted(COMMON_INPUT);
    private static final String SOURCE_OUTPUT = """
            {"type":"object","required":["schemaVersion","sourceRevisionId","sourceKind","sourceObjectId","sourceRevision","contentHash","chunkCount","cursor","nextCursor","complete","segment"],"properties":{
              "schemaVersion":{"const":"creative-source-segment-page/v1"},
              "sourceRevisionId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "sourceKind":{"type":"string","minLength":1,"maxLength":40},
              "sourceObjectId":{"type":"string","minLength":1,"maxLength":128},
              "sourceRevision":{"type":"string","minLength":1,"maxLength":128},
              "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "chunkCount":{"type":"integer","minimum":0},
              "cursor":%s,
              "nextCursor":{"anyOf":[
                %s,{"type":"null"}]},
              "complete":{"type":"boolean"},
              "segment":{"anyOf":[
                %s,{"type":"null"}]}
            },"additionalProperties":false}
            """.formatted(CURSOR, CURSOR, SEGMENT);
    private static final String ARTIFACT_OUTPUT = """
            {"type":"object","required":["schemaVersion","artifactId","artifactRef","artifactType","revision","contentHash","chunkCount","cursor","nextCursor","complete","segment"],"properties":{
              "schemaVersion":{"const":"creative-artifact-segment-page/v1"},
              "artifactId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "artifactRef":{"type":"string","pattern":"^artifact:[A-Za-z0-9][A-Za-z0-9._:-]{0,63}:[1-9][0-9]{0,18}$"},
              "artifactType":{"enum":["CREATIVE_TEXT","OUTLINE","SCREENPLAY","REVIEW_REPORT"]},
              "revision":{"type":"integer","minimum":1},
              "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "chunkCount":{"type":"integer","minimum":1},
              "cursor":%s,
              "nextCursor":{"anyOf":[
                %s,{"type":"null"}]},
              "complete":{"type":"boolean"},
              "segment":%s
            },"additionalProperties":false}
            """.formatted(CURSOR, CURSOR, SEGMENT);

    @Resource private CreativeSourceSegmentReadService sourceSegments;
    @Resource private CreativeArtifactSegmentReadService artifactSegments;

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(
                descriptor(SOURCE_SEGMENT_READ, "Read source text segment",
                        "Read one exact UTF-16-safe segment from an authorized immutable source.",
                        SOURCE_INPUT, SOURCE_OUTPUT),
                descriptor(ARTIFACT_SEGMENT_READ, "Read artifact text segment",
                        "Read one exact UTF-16-safe segment from an artifact frozen as a plan input.",
                        ARTIFACT_INPUT, ARTIFACT_OUTPUT));
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        Object result;
        if (SOURCE_SEGMENT_READ.equals(invocation.getToolCode())) {
            result = sourceSegments.read(invocation, JSON.parseObject(
                    invocation.getArgumentsJson(), CreativeSegmentRequests.SourceRead.class));
        } else if (ARTIFACT_SEGMENT_READ.equals(invocation.getToolCode())) {
            result = artifactSegments.read(invocation, JSON.parseObject(
                    invocation.getArgumentsJson(), CreativeSegmentRequests.ArtifactRead.class));
        } else {
            throw new IllegalArgumentException("Unsupported creative segment tool");
        }
        String output = JSON.toJSONString(result, JSONWriter.Feature.WriteNulls);
        if (output.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw new IllegalStateException("Creative segment output exceeds its byte budget");
        }
        return RuntimeToolResult.succeeded(output);
    }

    private RuntimeToolRegistry.Descriptor descriptor(String code, String name, String description,
                                                       String input, String output) {
        return new RuntimeToolRegistry.Descriptor(code, VERSION, SecureUtil.sha256(input + "\n" + output),
                name, description, input, output, "READ", false, "REQUEST_DIGEST",
                false, "REPLAY_SAFE", List.of("TEXT"));
    }
}
