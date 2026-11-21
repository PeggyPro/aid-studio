package com.aid.assistant.creative.observation;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.creative.service.CreativeTextSegmenter;
import com.aid.assistant.creative.tool.CreativeAuditRuntimeToolProvider;
import com.aid.assistant.creative.tool.CreativeRuntimeToolProvider;
import com.aid.assistant.creative.tool.CreativeSegmentRuntimeToolProvider;
import com.aid.assistant.service.AssistantToolObservationProjector;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Preserves exact text while keeping it inside an explicit untrusted-data envelope. */
@Component
public class CreativeUntrustedTextObservationProjector implements AssistantToolObservationProjector {
    private static final String OBSERVATION_SCHEMA = "assistant-untrusted-text-observation/v1";
    private static final Set<String> SUPPORTED = Set.of(
            CreativeRuntimeToolProvider.SOURCE_CHUNK_READ,
            CreativeAuditRuntimeToolProvider.ARTIFACT_CHUNK_READ,
            CreativeSegmentRuntimeToolProvider.SOURCE_SEGMENT_READ,
            CreativeSegmentRuntimeToolProvider.ARTIFACT_SEGMENT_READ);

    @Resource private RuntimeToolRegistry tools;

    @Override
    public boolean supports(String toolCode) {
        return toolCode != null && SUPPORTED.contains(toolCode);
    }

    @Override
    public JSONObject project(Input input) {
        if (input == null || !supports(input.toolCode()) || input.maxUtf8Bytes() < 1) invalid("identity");
        RuntimeToolRegistry.Descriptor descriptor = tools.require(input.toolCode());
        if (!Objects.equals(descriptor.contractVersion(), input.contractVersion())
                || !Objects.equals(descriptor.schemaDigest(), input.schemaDigest())
                || !supportedVersion(input.toolCode(), input.contractVersion())) invalid("contract");
        JSONObject arguments = object(input.argumentsJson(), "arguments");
        JSONObject output = object(input.outputJson(), "output");
        JSONObject page;
        if (CreativeRuntimeToolProvider.SOURCE_CHUNK_READ.equals(input.toolCode())) {
            page = chunkPage(arguments, output, true);
        } else if (CreativeAuditRuntimeToolProvider.ARTIFACT_CHUNK_READ.equals(input.toolCode())) {
            page = chunkPage(arguments, output, false);
        } else if (CreativeSegmentRuntimeToolProvider.SOURCE_SEGMENT_READ.equals(input.toolCode())) {
            page = segmentPage(arguments, output, true);
        } else {
            page = segmentPage(arguments, output, false);
        }
        JSONObject result = new JSONObject();
        result.put("schemaVersion", OBSERVATION_SCHEMA);
        result.put("untrustedData", true);
        result.put("contentDisposition", "UNTRUSTED_SOURCE_DATA");
        result.put("toolCode", input.toolCode());
        result.put("contractVersion", input.contractVersion());
        result.put("schemaDigest", input.schemaDigest());
        result.put("offsetUnit", "UTF16_CODE_UNIT_END_EXCLUSIVE");
        result.put("hashAlgorithm", "SHA256_UTF8");
        result.put("page", page);
        int bytes = result.toJSONString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > input.maxUtf8Bytes()) {
            throw new ServiceException("ASSISTANT_RESULT_OBSERVATION_BYTE_BUDGET_EXCEEDED");
        }
        return result;
    }

    private JSONObject chunkPage(JSONObject arguments, JSONObject output, boolean source) {
        requireKeys(arguments, source
                ? Set.of("sourceRevisionId", "afterChunkIndex", "limit")
                : Set.of("artifactId", "afterChunkIndex", "limit"), true);
        String idKey = source ? "sourceRevisionId" : "artifactId";
        String identity = positiveId(arguments.getString(idKey), idKey);
        int after = nullableInt(arguments, "afterChunkIndex", -1);
        int limit = nullableInt(arguments, "limit", 20);
        if (after < -1 || limit < 1 || limit > 100) invalid("cursor");
        Set<String> topKeys = source
                ? Set.of("sourceRevisionId", "sourceKind", "sourceObjectId", "sourceRevision", "contentHash",
                "chunkCount", "nextAfterChunkIndex", "complete", "chunks")
                : Set.of("artifactId", "artifactRef", "artifactType", "revision", "contentHash",
                "chunkCount", "nextAfterChunkIndex", "complete", "chunks");
        requireKeys(output, topKeys, false);
        if (!Objects.equals(identity, positiveId(output.getString(idKey), idKey))) invalid("identity");
        String wholeHash = digest(output.getString("contentHash"), "contentHash");
        Integer chunkCount = output.getInteger("chunkCount");
        Integer next = output.getInteger("nextAfterChunkIndex");
        Boolean complete = output.getBoolean("complete");
        JSONArray rawChunks = output.getJSONArray("chunks");
        if (chunkCount == null || chunkCount < 0 || next == null || next < -1 || complete == null
                || rawChunks == null || rawChunks.size() > limit || rawChunks.size() > 100) invalid("page");
        if (!source && chunkCount < 1) invalid("chunkCount");

        JSONArray chunks = new JSONArray();
        StringBuilder completeText = new StringBuilder();
        int expectedIndex = after + 1;
        Integer expectedStart = after < 0 ? 0 : null;
        for (Object item : rawChunks) {
            if (!(item instanceof JSONObject)) invalid("chunk");
            JSONObject raw = (JSONObject) item;
            requireKeys(raw, source
                    ? Set.of("chunkIndex", "charStart", "charEnd", "charCount", "wordCount",
                    "locator", "contentHash", "content")
                    : Set.of("chunkIndex", "charStart", "charEnd", "charCount", "wordCount",
                    "contentHash", "content"), source);
            JSONObject chunk = exactChunk(raw, expectedIndex, expectedStart);
            expectedStart = chunk.getInteger("charEnd");
            expectedIndex++;
            completeText.append(chunk.getString("content"));
            chunks.add(chunk);
        }
        int expectedNext = chunks.isEmpty() ? after : chunks.getJSONObject(chunks.size() - 1).getInteger("chunkIndex");
        if (next != expectedNext || next >= chunkCount && !(chunkCount == 0 && next == -1)) invalid("cursor");
        if (complete) {
            if (!(chunkCount == 0 && next == -1 || chunkCount > 0 && next == chunkCount - 1)) invalid("complete");
            if (after == -1 && !Objects.equals(wholeHash, SecureUtil.sha256(completeText.toString()))) {
                invalid("contentHash");
            }
        } else if (chunkCount == 0 || next >= chunkCount - 1) {
            invalid("complete");
        }

        JSONObject page = copyScalars(output, topKeys, Set.of("chunks"));
        page.put("chunks", chunks);
        return page;
    }

    private JSONObject exactChunk(JSONObject raw, int expectedIndex, Integer expectedStart) {
        Integer index = raw.getInteger("chunkIndex");
        Integer start = raw.getInteger("charStart");
        Integer end = raw.getInteger("charEnd");
        Integer count = raw.getInteger("charCount");
        Integer words = raw.getInteger("wordCount");
        String content = raw.getString("content");
        String hash = digest(raw.getString("contentHash"), "contentHash");
        if (index == null || index != expectedIndex || start == null || start < 0
                || expectedStart != null && !Objects.equals(start, expectedStart)
                || end == null || count == null || words == null || words < 0 || content == null
                || !CreativeTextSegmenter.wellFormedUtf16(content)
                || end != start + content.length() || count != content.length()
                || !Objects.equals(hash, SecureUtil.sha256(content))) invalid("chunk");
        JSONObject result = new JSONObject();
        result.put("chunkIndex", index);
        result.put("charStart", start);
        result.put("charEnd", end);
        result.put("charCount", count);
        result.put("wordCount", words);
        result.put("contentHash", hash);
        result.put("content", content);
        return result;
    }

    private JSONObject segmentPage(JSONObject arguments, JSONObject output, boolean source) {
        requireKeys(arguments, source
                ? Set.of("sourceRevisionId", "chunkIndex", "charOffset", "maxUtf8Bytes")
                : Set.of("artifactId", "chunkIndex", "charOffset", "maxUtf8Bytes"), true);
        String idKey = source ? "sourceRevisionId" : "artifactId";
        String identity = positiveId(arguments.getString(idKey), idKey);
        int requestedChunk = nullableInt(arguments, "chunkIndex", 0);
        int requestedOffset = nullableInt(arguments, "charOffset", 0);
        int requestedBytes = nullableInt(arguments, "maxUtf8Bytes", CreativeTextSegmenter.DEFAULT_MAX_UTF8_BYTES);
        if (requestedChunk < 0 || requestedOffset < 0
                || requestedBytes < CreativeTextSegmenter.MIN_MAX_UTF8_BYTES
                || requestedBytes > CreativeTextSegmenter.MAX_MAX_UTF8_BYTES) invalid("cursor");
        Set<String> topKeys = source
                ? Set.of("schemaVersion", "sourceRevisionId", "sourceKind", "sourceObjectId", "sourceRevision",
                "contentHash", "chunkCount", "cursor", "nextCursor", "complete", "segment")
                : Set.of("schemaVersion", "artifactId", "artifactRef", "artifactType", "revision",
                "contentHash", "chunkCount", "cursor", "nextCursor", "complete", "segment");
        requireKeys(output, topKeys, false);
        String expectedSchema = source ? "creative-source-segment-page/v1" : "creative-artifact-segment-page/v1";
        if (!Objects.equals(expectedSchema, output.getString("schemaVersion"))
                || !Objects.equals(identity, positiveId(output.getString(idKey), idKey))) invalid("identity");
        String wholeHash = digest(output.getString("contentHash"), "contentHash");
        Integer chunkCount = output.getInteger("chunkCount");
        Boolean complete = output.getBoolean("complete");
        JSONObject cursor = output.getJSONObject("cursor");
        JSONObject next = output.getJSONObject("nextCursor");
        JSONObject rawSegment = output.getJSONObject("segment");
        if (chunkCount == null || chunkCount < (source ? 0 : 1) || complete == null || cursor == null) invalid("page");
        requireCursor(cursor, requestedChunk, requestedOffset);
        if (chunkCount == 0) {
            if (!source || !complete || rawSegment != null || next != null
                    || requestedChunk != 0 || requestedOffset != 0) invalid("empty");
        } else {
            if (rawSegment == null || requestedChunk >= chunkCount) invalid("segment");
            JSONObject segment = exactSegment(rawSegment, requestedChunk, requestedOffset, requestedBytes);
            output = copyScalars(output, topKeys, Set.of("cursor", "nextCursor", "segment"));
            output.put("cursor", copyCursor(cursor));
            output.put("segment", segment);
            validateNext(next, complete, chunkCount, segment, requestedChunk, requestedOffset);
            output.put("nextCursor", next == null ? null : copyCursor(next));
            if (complete && requestedChunk == 0 && requestedOffset == 0
                    && segment.getInteger("parentChunkIndex") == 0
                    && segment.getInteger("parentCharStart") == 0
                    && segment.getInteger("charEnd").equals(segment.getInteger("parentCharEnd"))
                    && !Objects.equals(wholeHash, SecureUtil.sha256(segment.getString("content")))) {
                invalid("contentHash");
            }
            return output;
        }
        JSONObject page = copyScalars(output, topKeys, Set.of("cursor", "nextCursor", "segment"));
        page.put("cursor", copyCursor(cursor));
        page.put("nextCursor", null);
        page.put("segment", null);
        return page;
    }

    private JSONObject exactSegment(JSONObject raw, int requestedChunk, int requestedOffset, int requestedBytes) {
        Set<String> keys = Set.of("parentChunkIndex", "parentCharStart", "parentCharEnd", "parentCharCount",
                "parentContentHash", "charStart", "charEnd", "charCount", "utf8ByteCount",
                "contentHash", "content");
        requireKeys(raw, keys, false);
        Integer parentIndex = raw.getInteger("parentChunkIndex");
        Integer parentStart = raw.getInteger("parentCharStart");
        Integer parentEnd = raw.getInteger("parentCharEnd");
        Integer parentCount = raw.getInteger("parentCharCount");
        Integer start = raw.getInteger("charStart");
        Integer end = raw.getInteger("charEnd");
        Integer count = raw.getInteger("charCount");
        Integer utf8Bytes = raw.getInteger("utf8ByteCount");
        String content = raw.getString("content");
        String parentHash = digest(raw.getString("parentContentHash"), "parentContentHash");
        String hash = digest(raw.getString("contentHash"), "contentHash");
        int actualBytes = content == null ? -1 : content.getBytes(StandardCharsets.UTF_8).length;
        if (parentIndex == null || parentIndex != requestedChunk || parentStart == null || parentStart < 0
                || parentEnd == null || parentCount == null || parentCount < 1
                || parentEnd != parentStart + parentCount || requestedOffset >= parentCount
                || start == null || start != parentStart + requestedOffset || end == null
                || count == null || count < 1 || content == null || !CreativeTextSegmenter.wellFormedUtf16(content)
                || end != start + content.length() || count != content.length()
                || utf8Bytes == null || utf8Bytes != actualBytes || utf8Bytes > requestedBytes
                || !Objects.equals(hash, SecureUtil.sha256(content))) invalid("segment");
        if (requestedOffset == 0 && count == parentCount && !Objects.equals(parentHash, hash)) invalid("parentHash");
        return copyScalars(raw, keys, Set.of());
    }

    private void validateNext(JSONObject next, boolean complete, int chunkCount, JSONObject segment,
                              int requestedChunk, int requestedOffset) {
        if (complete) {
            if (next != null || requestedChunk != chunkCount - 1
                    || !Objects.equals(segment.getInteger("charEnd"), segment.getInteger("parentCharEnd"))) {
                invalid("complete");
            }
            return;
        }
        if (next == null) invalid("nextCursor");
        Integer nextChunk = next.getInteger("chunkIndex");
        Integer nextOffset = next.getInteger("charOffset");
        requireKeys(next, Set.of("chunkIndex", "charOffset"), false);
        boolean withinParent = !Objects.equals(segment.getInteger("charEnd"), segment.getInteger("parentCharEnd"));
        if (withinParent) {
            if (!Objects.equals(nextChunk, requestedChunk)
                    || !Objects.equals(nextOffset, requestedOffset + segment.getInteger("charCount"))) {
                invalid("nextCursor");
            }
        } else if (!Objects.equals(nextChunk, requestedChunk + 1) || !Objects.equals(nextOffset, 0)
                || nextChunk >= chunkCount) {
            invalid("nextCursor");
        }
    }

    private JSONObject copyCursor(JSONObject source) {
        requireKeys(source, Set.of("chunkIndex", "charOffset"), false);
        Integer chunk = source.getInteger("chunkIndex");
        Integer offset = source.getInteger("charOffset");
        if (chunk == null || chunk < 0 || offset == null || offset < 0) invalid("cursor");
        JSONObject result = new JSONObject();
        result.put("chunkIndex", chunk);
        result.put("charOffset", offset);
        return result;
    }

    private void requireCursor(JSONObject cursor, int chunk, int offset) {
        JSONObject value = copyCursor(cursor);
        if (!Objects.equals(value.getInteger("chunkIndex"), chunk)
                || !Objects.equals(value.getInteger("charOffset"), offset)) invalid("cursor");
    }

    private JSONObject copyScalars(JSONObject source, Set<String> keys, Set<String> excluded) {
        JSONObject result = new JSONObject();
        for (String key : new LinkedHashSet<>(keys)) {
            if (!excluded.contains(key)) result.put(key, source.get(key));
        }
        return result;
    }

    private void requireKeys(JSONObject object, Set<String> allowed, boolean optionalAllowed) {
        if (object == null || !allowed.containsAll(object.keySet())) invalid("fields");
        if (!optionalAllowed && !object.keySet().containsAll(allowed)) invalid("fields");
    }

    private JSONObject object(String value, String field) {
        try {
            JSONObject result = JSON.parseObject(value);
            if (result == null) invalid(field);
            return result;
        } catch (RuntimeException error) {
            if (error instanceof ServiceException service) throw service;
            throw new ServiceException("ASSISTANT_RESULT_OBSERVATION_INVALID");
        }
    }

    private int nullableInt(JSONObject object, String key, int fallback) {
        Integer value = object.getInteger(key);
        return value == null ? fallback : value;
    }

    private String positiveId(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid(field);
        return value;
    }

    private String digest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) invalid(field);
        return value;
    }

    private boolean supportedVersion(String code, String version) {
        if (CreativeRuntimeToolProvider.SOURCE_CHUNK_READ.equals(code)) return "1.3.0".equals(version);
        if (CreativeAuditRuntimeToolProvider.ARTIFACT_CHUNK_READ.equals(code)) return "1.0.0".equals(version);
        return CreativeSegmentRuntimeToolProvider.VERSION.equals(version);
    }

    private void invalid(String field) {
        throw new ServiceException("ASSISTANT_RESULT_OBSERVATION_INVALID:" + field);
    }
}
