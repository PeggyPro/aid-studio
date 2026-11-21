package com.aid.assistant.creative.series;

import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.assistant.creative.series.mapper.SeriesCurrentScriptProjection;
import com.aid.assistant.error.AssistantServiceException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Deterministic project episode-set baseline used by catalog and series adoption. */
public final class SeriesProjectState {
    private SeriesProjectState() {
    }

    public static Snapshot snapshot(AidComicProject project, List<AidComicEpisode> episodes,
                                    List<SeriesCurrentScriptProjection> currentScripts) {
        List<AidComicEpisode> orderedEpisodes = new ArrayList<>(episodes == null ? List.of() : episodes);
        orderedEpisodes.sort(Comparator.comparing(AidComicEpisode::getEpisodeNo,
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(AidComicEpisode::getId, Comparator.nullsLast(Long::compareTo)));
        List<SeriesCurrentScriptProjection> orderedScripts = new ArrayList<>(
                currentScripts == null ? List.of() : currentScripts);
        orderedScripts.sort(Comparator.comparing(SeriesCurrentScriptProjection::getEpisodeId,
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(SeriesCurrentScriptProjection::getId,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        Map<Long, SeriesCurrentScriptProjection> byEpisode = new LinkedHashMap<>();
        for (SeriesCurrentScriptProjection script : orderedScripts) {
            if (script.getId() == null || script.getEpisodeId() == null
                    || !project.getId().equals(script.getProjectId()) || script.getComicVersion() == null
                    || script.getComicVersion() < 1 || !Integer.valueOf(1).equals(script.getStatus())
                    || !"0".equals(script.getDelFlag())) {
                throw new AssistantServiceException(HttpStatus.CONFLICT.value(),
                        "SERIES_CURRENT_SCRIPT_INVALID", "Current script metadata is invalid",
                        script.getId() == null ? null : String.valueOf(script.getId()));
            }
            requireHash(script.getContentHash(), script.getId());
            if (byEpisode.putIfAbsent(script.getEpisodeId(), script) != null) {
                throw new AssistantServiceException(HttpStatus.CONFLICT.value(),
                        "SERIES_CURRENT_SCRIPT_DUPLICATE", "Episode has multiple current scripts",
                        String.valueOf(script.getEpisodeId()));
            }
        }
        List<Map<String, Object>> digestItems = new ArrayList<>(orderedEpisodes.size());
        long maxEpisodeNo = 0;
        Set<Long> episodeNumbers = new HashSet<>();
        Set<Long> episodeIds = new HashSet<>();
        for (AidComicEpisode episode : orderedEpisodes) {
            if (episode.getId() == null || episode.getEpisodeNo() == null || episode.getEpisodeNo() < 1
                    || episode.getEpisodeNo() > Integer.MAX_VALUE - SeriesScriptSplitPolicy.MAX_EPISODES
                    || !episodeNumbers.add(episode.getEpisodeNo()) || !episodeIds.add(episode.getId())) {
                throw new AssistantServiceException(HttpStatus.CONFLICT.value(),
                        "SERIES_EPISODE_SET_INVALID",
                        "Series episode numbers must be positive, unique and page-safe",
                        episode.getId() == null ? null : String.valueOf(episode.getId()));
            }
            SeriesCurrentScriptProjection script = byEpisode.get(episode.getId());
            String contentHash = script == null ? emptyContentHash()
                    : requireHash(script.getContentHash(), script.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("episodeId", id(episode.getId()));
            item.put("episodeNo", episode.getEpisodeNo());
            item.put("title", episode.getComicTitle());
            item.put("description", episode.getComicDesc());
            item.put("status", episode.getStatus());
            item.put("scriptId", script == null ? null : id(script.getId()));
            item.put("scriptVersion", script == null ? null : script.getComicVersion());
            item.put("scriptContentHash", contentHash);
            item.put("targetRevision", revision(script));
            digestItems.add(item);
            if (episode.getEpisodeNo() != null) maxEpisodeNo = Math.max(maxEpisodeNo, episode.getEpisodeNo());
        }
        if (!episodeIds.containsAll(byEpisode.keySet())) {
            throw new AssistantServiceException(HttpStatus.CONFLICT.value(),
                    "SERIES_CURRENT_SCRIPT_ORPHANED", "Current script is not attached to an active episode", null);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", "assistant-series-episode-set/v1");
        envelope.put("projectId", id(project.getId()));
        envelope.put("projectType", project.getProjectType());
        envelope.put("episodes", digestItems);
        return new Snapshot(project, List.copyOf(orderedEpisodes), Map.copyOf(byEpisode),
                SeriesAdoptionDigest.sha256Canonical(envelope), maxEpisodeNo);
    }

    public static String revision(SeriesCurrentScriptProjection script) {
        if (script == null) return "script:0:v0:" + emptyContentHash();
        return "script:" + script.getId() + ":v" + script.getComicVersion() + ":"
                + requireHash(script.getContentHash(), script.getId());
    }

    public static String revision(AidComicScript script) {
        if (script == null) return "script:0:v0:" + emptyContentHash();
        return "script:" + script.getId() + ":v" + script.getComicVersion() + ":"
                + com.aid.script.helper.UserScriptContentHash.calculate(script.getOriginalText());
    }

    public static int effectiveChars(String value) {
        if (value == null || value.isBlank()) return 0;
        return value.replaceAll("(?i)</?br\\s*/?>", "").replaceAll("\\s+", "").length();
    }

    private static String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String emptyContentHash() {
        return com.aid.script.helper.UserScriptContentHash.calculate("");
    }

    private static String requireHash(String value, Long scriptId) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new AssistantServiceException(HttpStatus.CONFLICT.value(),
                    "SERIES_SCRIPT_HASH_INVALID", "Current script content hash is invalid",
                    scriptId == null ? null : String.valueOf(scriptId));
        }
        return value;
    }

    public record Snapshot(AidComicProject project, List<AidComicEpisode> episodes,
                           Map<Long, SeriesCurrentScriptProjection> currentScripts, String episodeSetDigest,
                           long maxEpisodeNo) {
        public AidComicEpisode episode(Long episodeId) {
            if (episodeId == null) return null;
            return episodes.stream().filter(value -> episodeId.equals(value.getId())).findFirst().orElse(null);
        }
    }
}
