package com.aid.assistant.creative.series;

import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.assistant.creative.series.dto.SeriesCatalogRequests;
import com.aid.assistant.creative.series.mapper.AssistantSeriesAdoptionMapper;
import com.aid.assistant.creative.series.mapper.SeriesCurrentScriptProjection;
import com.aid.assistant.creative.series.vo.SeriesCatalogViews;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.enums.ProjectTypeEnum;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.tool.RuntimeToolInvocation;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads a body-free, project-bound catalog for a project-scope Screenwriter run. */
@Service
public class CreativeSeriesCatalogService {
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AssistantSeriesAdoptionMapper series;

    public SeriesCatalogViews.Page read(RuntimeToolInvocation invocation, SeriesCatalogRequests.Read request) {
        if (invocation == null || invocation.getRunId() == null || invocation.getUserId() == null) {
            fail(HttpStatus.FORBIDDEN, "SERIES_CATALOG_SCOPE_REQUIRED",
                    "Series catalog requires a trusted runtime scope", null);
        }
        int after = request == null || request.getAfterEpisodeNo() == null ? 0 : request.getAfterEpisodeNo();
        int limit = request == null || request.getLimit() == null ? 50 : request.getLimit();
        if (after < 0 || limit < 1 || limit > 100) {
            fail(HttpStatus.BAD_REQUEST, "SERIES_CATALOG_PAGE_INVALID", "Series catalog page is invalid", null);
        }
        AidRuntimeRun run = runs.selectById(invocation.getRunId());
        if (run == null || !"0".equals(run.getDelFlag()) || !Objects.equals(run.getUserId(), invocation.getUserId())) {
            fail(HttpStatus.FORBIDDEN, "SERIES_CATALOG_RUN_FORBIDDEN", "Series catalog run is not accessible", null);
        }
        AidAssistantSession session = series.selectSession(run.getSessionId());
        requireProjectScope(session, invocation.getUserId());
        if (!Objects.equals(run.getProjectId(), session.getProjectId())) {
            fail(HttpStatus.CONFLICT, "SERIES_CATALOG_RUN_SCOPE_CONFLICT",
                    "Series catalog run does not match the session project", null);
        }
        AidComicProject project = series.selectProject(session.getProjectId());
        requireProject(project, invocation.getUserId());
        SeriesProjectState.Snapshot state = SeriesProjectState.snapshot(project,
                series.selectEpisodes(project.getId()), series.selectCurrentScripts(project.getId()));
        List<AidComicEpisode> available = state.episodes().stream()
                .filter(value -> value.getEpisodeNo() != null && value.getEpisodeNo() > after).toList();
        boolean complete = available.size() <= limit;
        int pageSize = Math.min(limit, available.size());
        List<Long> pageEpisodeIds = available.subList(0, pageSize).stream().map(AidComicEpisode::getId).toList();
        Map<Long, AidComicScript> bodies = new HashMap<>();
        if (!pageEpisodeIds.isEmpty()) {
            for (AidComicScript body : series.selectCurrentScriptBodies(project.getId(), pageEpisodeIds)) {
                if (body.getEpisodeId() != null) bodies.putIfAbsent(body.getEpisodeId(), body);
            }
        }
        List<SeriesCatalogViews.Item> items = new ArrayList<>(pageSize);
        for (int index = 0; index < pageSize; index++) {
            AidComicEpisode episode = available.get(index);
            SeriesCurrentScriptProjection script = state.currentScripts().get(episode.getId());
            AidComicScript body = bodies.get(episode.getId());
            if (script != null && (body == null || !Objects.equals(script.getId(), body.getId())
                    || !Objects.equals(script.getComicVersion(), body.getComicVersion())
                    || !Objects.equals(script.getContentHash(),
                    com.aid.script.helper.UserScriptContentHash.calculate(body.getOriginalText())))) {
                fail(HttpStatus.CONFLICT, "SERIES_CATALOG_CHANGED",
                        "Series catalog changed while the page was being read", String.valueOf(episode.getId()));
            }
            items.add(new SeriesCatalogViews.Item(String.valueOf(episode.getId()), episode.getEpisodeNo(),
                    episode.getComicTitle(), script == null ? null : String.valueOf(script.getId()),
                    script == null ? null : script.getComicVersion(), script == null ? null : script.getContentHash(),
                    SeriesProjectState.revision(script),
                    body == null ? 0 : SeriesProjectState.effectiveChars(body.getOriginalText())));
        }
        Integer next = complete || items.isEmpty() ? null : Math.toIntExact(items.get(items.size() - 1).episodeNo());
        return new SeriesCatalogViews.Page("aid-creative-series-catalog/v1", String.valueOf(project.getId()),
                project.getProjectType(), state.episodeSetDigest(), List.copyOf(items), next, complete);
    }

    static void requireProjectScope(AidAssistantSession session, Long userId) {
        if (session == null || !Objects.equals(session.getUserId(), userId)
                || !"ASSISTANT".equals(session.getSessionType())
                || !"SCREENWRITER".equals(session.getModuleCode()) || session.getProjectId() == null
                || !Objects.equals(0L, session.getEpisodeId())) {
            failStatic(HttpStatus.FORBIDDEN, "SERIES_PROJECT_SCOPE_REQUIRED",
                    "A project-scope Screenwriter session is required", null);
        }
    }

    static void requireProject(AidComicProject project, Long userId) {
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            failStatic(HttpStatus.NOT_FOUND, "SERIES_PROJECT_NOT_FOUND", "Series project is not accessible", null);
        }
        if (!Objects.equals(ProjectTypeEnum.SERIES.getValue(), project.getProjectType())) {
            failStatic(HttpStatus.UNPROCESSABLE_ENTITY, "SERIES_PROJECT_TYPE_REQUIRED",
                    "Series adoption requires a series project", project.getProjectType());
        }
    }

    private void fail(HttpStatus status, String code, String message, Object details) {
        failStatic(status, code, message, details);
    }

    private static void failStatic(HttpStatus status, String code, String message, Object details) {
        throw new AssistantServiceException(status.value(), code, message, details);
    }
}
