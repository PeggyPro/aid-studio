package com.aid.assistant.creative.series;

import com.aid.assistant.creative.series.SeriesArtifactSnapshotService.Snapshot;
import com.aid.assistant.creative.series.SeriesScriptSplitPolicy.Segment;
import com.aid.assistant.creative.series.SeriesScriptSplitPolicy.SplitPlan;
import com.aid.assistant.error.AssistantServiceException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Canonical, bounded, recomputable APPEND capability snapshot. It is not an authorization token. */
@Component
public class SeriesSplitCapabilitySnapshotCodec {
    public static final String SCHEMA_VERSION = "assistant-series-split-capability/v1";
    public static final int MAX_JSON_BYTES = 128 * 1024;
    private final ObjectMapper strict = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public String encode(Snapshot artifact, SplitPlan plan) {
        List<Descriptor> values = plan.segments().stream().map(this::descriptor).toList();
        CapabilitySnapshot snapshot = new CapabilitySnapshot(SCHEMA_VERSION,
                SeriesScriptSplitPolicy.CONTRACT_VERSION, String.valueOf(artifact.artifact().getId()),
                artifact.contentHash(), plan.episodeKeyword(), values);
        String canonical = SeriesAdoptionDigest.canonicalJson(snapshot);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) fail("SERIES_SPLIT_SNAPSHOT_TOO_LARGE",
                "Series split capability snapshot exceeds its byte limit", bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public CapabilitySnapshot decode(String token) {
        if (token == null || token.isBlank() || token.length() > 180_000) {
            fail("SERIES_SPLIT_SNAPSHOT_INVALID", "Series split capability snapshot is invalid", null);
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length > MAX_JSON_BYTES) fail("SERIES_SPLIT_SNAPSHOT_TOO_LARGE",
                    "Series split capability snapshot exceeds its byte limit", bytes.length);
            String json = new String(bytes, StandardCharsets.UTF_8);
            CapabilitySnapshot value = strict.readValue(json, CapabilitySnapshot.class);
            String canonical = SeriesAdoptionDigest.canonicalJson(value);
            if (!canonical.equals(json) || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(canonical.getBytes(StandardCharsets.UTF_8)).equals(token)) {
                fail("SERIES_SPLIT_SNAPSHOT_NOT_CANONICAL",
                        "Series split capability snapshot is not canonical", null);
            }
            validate(value);
            return value;
        } catch (AssistantServiceException failure) {
            throw failure;
        } catch (Exception error) {
            fail("SERIES_SPLIT_SNAPSHOT_INVALID", "Series split capability snapshot is invalid", null);
            return null;
        }
    }

    public void assertRecomputed(CapabilitySnapshot candidate, Snapshot artifact, SplitPlan plan) {
        CapabilitySnapshot trusted = decode(encode(artifact, plan));
        if (!Objects.equals(candidate, trusted)) {
            fail("SERIES_SPLIT_SNAPSHOT_CONFLICT",
                    "Series split capability snapshot does not match the trusted artifact", null);
        }
    }

    private void validate(CapabilitySnapshot value) {
        if (value == null || !SCHEMA_VERSION.equals(value.schemaVersion())
                || !SeriesScriptSplitPolicy.CONTRACT_VERSION.equals(value.splitterContractVersion())
                || !id(value.artifactId()) || !sha(value.artifactContentHash())
                || value.episodeKeyword() != null && (value.episodeKeyword().isBlank()
                || value.episodeKeyword().length() > SeriesScriptSplitPolicy.MAX_KEYWORD_CHARS)
                || value.segments() == null || value.segments().isEmpty()
                || value.segments().size() > SeriesScriptSplitPolicy.MAX_EPISODES) {
            fail("SERIES_SPLIT_SNAPSHOT_INVALID", "Series split capability snapshot is invalid", null);
        }
        Set<Integer> sequence = new HashSet<>();
        int previousEnd = 0;
        for (int index = 0; index < value.segments().size(); index++) {
            Descriptor item = value.segments().get(index);
            if (item == null || item.sequenceNo() != index + 1 || !sequence.add(item.sequenceNo())
                    || item.sourceCharStart() < 0 || item.sourceCharEnd() <= item.sourceCharStart()
                    || item.sourceCharStart() < previousEnd || !sha(item.segmentContentHash())
                    || item.effectiveCharCount() < 1
                    || item.effectiveCharCount() > SeriesScriptSplitPolicy.MAX_EPISODE_EFFECTIVE_CHARS
                    || item.proposedTitle() == null || item.proposedTitle().length() > 50
                    || item.proposedDescription() == null || item.proposedDescription().length() > 23) {
                fail("SERIES_SPLIT_SNAPSHOT_DESCRIPTOR_INVALID",
                        "Series split capability snapshot contains an invalid descriptor", index);
            }
            previousEnd = item.sourceCharEnd();
        }
    }

    private Descriptor descriptor(Segment value) {
        return new Descriptor(value.sequenceNo(), value.sourceCharStart(), value.sourceCharEnd(),
                value.contentHash(), value.effectiveCharCount(), value.title(), value.description());
    }

    private boolean id(String value) {
        return value != null && value.matches("^[1-9][0-9]{0,18}$");
    }

    private boolean sha(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private void fail(String code, String message, Object details) {
        throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(), code, message, details);
    }

    public record CapabilitySnapshot(String schemaVersion, String splitterContractVersion,
                                     String artifactId, String artifactContentHash,
                                     String episodeKeyword, List<Descriptor> segments) {
        public CapabilitySnapshot {
            segments = segments == null ? null : List.copyOf(new ArrayList<>(segments));
        }
    }

    public record Descriptor(int sequenceNo, int sourceCharStart, int sourceCharEnd,
                             String segmentContentHash, int effectiveCharCount,
                             String proposedTitle, String proposedDescription) {
    }
}
