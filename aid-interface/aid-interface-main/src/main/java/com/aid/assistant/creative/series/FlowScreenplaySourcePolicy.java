package com.aid.assistant.creative.series;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.service.AssistantEntryTransport;
import com.aid.assistant.source.service.AssistantDocumentAccess;
import com.aid.script.helper.ScriptEpisodeSplitter;
import com.aid.script.service.ScriptLimitsService;
import com.aid.script.service.IUserScriptBusinessService;
import com.aid.script.helper.UserScriptContentHash;
import com.aid.assistant.creative.source.ScriptCreativeSourceProvider;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Applies the single-episode import rules at upload finalization and before an Agent run. */
@Component
@RequiredArgsConstructor
public class FlowScreenplaySourcePolicy {
    public static final String CONTRACT = "flow-screenplay/v1";
    public static final String PIN = "_flowScreenplay";
    public static final String REALTIME_PATH = "/ws/canvas/assistant";
    private static final int MAX_IMPORTED_CODE_UNITS = 120_000;
    private final ScriptLimitsService limits;
    private final AssistantDocumentAccess documents;
    private final AidRuntimeSourceChunkMapper chunks;
    private final IUserScriptBusinessService scripts;

    public static boolean applies(AidAssistantSession session) {
        return session != null && supportsEntry(session.getModuleCode(), AssistantEntryTransport.of(session))
                && "ADAPT".equals(session.getMode())
                && session.getProjectId() != null && session.getProjectId() > 0
                && session.getEpisodeId() != null && session.getEpisodeId() >= 0;
    }

    public static boolean supportsEntry(String moduleCode, String transport) {
        return "SCREENWRITER".equals(moduleCode) && "MAIN".equals(transport);
    }

    public void validateReferences(Long userId, AidAssistantSession session, List<ResolvedContextRef> references) {
        if (!applies(session)) return;
        int total = 0;
        for (ResolvedContextRef resolved : references) {
            if (!"DOCUMENT".equals(resolved.contextRef().getKind())) continue;
            total += readDocument(userId, session, resolved).length();
            if (total > MAX_IMPORTED_CODE_UNITS)
                throw invalid("FLOW_SCRIPT_SOURCE_TOO_LARGE", "参考文档合计过长，请减少附件");
        }
    }

    public JSONObject pin(Long userId, AidAssistantSession session, List<ResolvedContextRef> references) {
        var script = scripts.selectUserScriptByProject(session.getProjectId(), session.getEpisodeId(), userId);
        int limit = episodeLimit(session);
        if (script != null && ScriptLimitsService.countChars(script.getOriginalText()) > limit) {
            throw invalid("FLOW_SCRIPT_EPISODE_TOO_LONG", "当前剧本超过" + limit + "字，请缩短后发送");
        }
        JSONObject pin = JSONObject.of("version", CONTRACT, "baseline", revision(script),
                "limit", limit);
        var selections = references.stream().filter(ref -> "SCRIPT".equals(ref.contextRef().getKind())
                && ref.contextRef().getSelection() != null).toList();
        if (selections.size() == 1 && script != null) {
            var selection = selections.get(0).contextRef().getSelection();
            String source = script.getOriginalText();
            if (source != null && selection.getStart() >= 0 && selection.getStart() <= selection.getEnd()
                    && selection.getEnd() <= source.length()
                    && SecureUtil.sha256(source.substring(selection.getStart(), selection.getEnd()))
                        .equals(selection.getSelectedTextHash())) {
                pin.put("selection", JSONObject.of("start", selection.getStart(), "end", selection.getEnd()));
                pin.put("outsideCount", ScriptLimitsService.countChars(source.substring(0, selection.getStart()))
                        + ScriptLimitsService.countChars(source.substring(selection.getEnd())));
            }
        }
        return pin;
    }

    public static String revision(com.aid.aid.domain.AidComicScript script) {
        if (script == null) return "script:0:v0:" + UserScriptContentHash.calculate("");
        return ScriptCreativeSourceProvider.revision(script, UserScriptContentHash.calculate(script.getOriginalText()));
    }

    public JSONObject modelInput(Long userId, AidAssistantSession session, JSONObject pin,
                                 List<ResolvedContextRef> references) {
        var script = scripts.selectUserScriptByProject(session.getProjectId(), session.getEpisodeId(), userId);
        if (!revision(script).equals(pin.getString("baseline"))) {
            throw new AssistantServiceException(409, "FLOW_SCRIPT_BASELINE_CHANGED",
                    "当前剧本已有新修改，请使用最新版本重新发送", null);
        }
        String current = script == null ? "" : java.util.Objects.toString(script.getOriginalText(), "");
        JSONArray sources = new JSONArray();
        JSONArray selections = new JSONArray();
        for (ResolvedContextRef resolved : references) {
            var ref = resolved.contextRef();
            if ("DOCUMENT".equals(ref.getKind())) {
                sources.add(JSONObject.of("source", "SINGLE_EPISODE_DOCUMENT", "text",
                        readDocument(userId, session, resolved)));
            } else if ("SCRIPT".equals(ref.getKind()) && ref.getSelection() != null) {
                var selection = ref.getSelection();
                if (selection.getStart() < 0 || selection.getStart() > selection.getEnd()
                        || selection.getEnd() > current.length()) throw incomplete();
                String selected = current.substring(selection.getStart(), selection.getEnd());
                if (!SecureUtil.sha256(selected).equals(selection.getSelectedTextHash())) throw incomplete();
                selections.add(JSONObject.of("start", selection.getStart(), "end", selection.getEnd(), "text", selected));
            }
        }
        return JSONObject.of("contract", CONTRACT, "currentScript", current, "selections", selections,
                "documents", sources, "maxEpisodeCharacters", episodeLimit(session));
    }

    private String readDocument(Long userId, AidAssistantSession session, ResolvedContextRef resolved) {
        var snapshot = documents.require(userId, resolved.contextRef().getDocumentId());
        var revision = snapshot.revision();
        if (revision.getCharCount() == null || revision.getCharCount() > MAX_IMPORTED_CODE_UNITS
                || revision.getChunkCount() == null || revision.getChunkCount() < 1
                || revision.getChunkCount() > 100) {
            throw invalid("FLOW_SCRIPT_SOURCE_TOO_LARGE", "参考剧本文本过长，请拆分后上传");
        }
        var parts = chunks.selectAuthorizedPage(userId, revision.getId(), -1, 100);
        if (parts.size() != revision.getChunkCount()) throw incomplete();
        StringBuilder source = new StringBuilder(revision.getCharCount());
        for (var part : parts) {
            if (part.getContentText() == null || part.getCharStart() == null
                    || part.getCharStart() != source.length()
                    || part.getContentText().length() > MAX_IMPORTED_CODE_UNITS - source.length()) {
                throw incomplete();
            }
            source.append(part.getContentText());
        }
        String text = source.toString();
        if (text.length() != revision.getCharCount() || !SecureUtil.sha256(text).equals(revision.getContentHash())) {
            throw incomplete();
        }
        validateDocument(session, text);
        return text;
    }

    public void validateDocument(AidAssistantSession session, String text) {
        int limit = episodeLimit(session);
        if (text.length() > MAX_IMPORTED_CODE_UNITS) {
            throw invalid("FLOW_SCRIPT_SOURCE_TOO_LARGE", "参考剧本文本过长，请拆分后上传");
        }
        var episodes = ScriptEpisodeSplitter.split(text, "第一集");
        var chapters = ScriptEpisodeSplitter.split(text, "第一话");
        if (episodes.size() + chapters.size() > 1) {
            throw invalid("FLOW_SCRIPT_MULTIPLE_EPISODES", "请上传单集剧本，整部剧本请到剧集管理导入");
        }
        if (episodes.isEmpty()) episodes = chapters;
        int count = ScriptLimitsService.countChars(text);
        if (count > limit) {
            throw new AssistantServiceException(422, "FLOW_SCRIPT_EPISODE_TOO_LONG",
                    "单集剧本超过" + limit + "字，请缩短后上传", Map.of("count", count, "limit", limit));
        }
        if (count == 0 || !episodes.isEmpty() && !episodes.get(0).isBodyPresent()) {
            throw invalid("FLOW_SCRIPT_SOURCE_EMPTY", "上传的单集剧本没有正文");
        }
    }

    public int episodeLimit(AidAssistantSession session) {
        return Math.min(10_000, session.getEpisodeId() == 0
                ? ScriptLimitsService.MOVIE_MAX_CHARS : limits.current().episodeMaxChars());
    }

    private AssistantServiceException incomplete() {
        return invalid("FLOW_SCRIPT_SOURCE_INCOMPLETE", "参考剧本未能完整读取，请重新上传");
    }

    private AssistantServiceException invalid(String code, String message) {
        return new AssistantServiceException(422, code, message, null);
    }
}
