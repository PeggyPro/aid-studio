package com.aid.assistant.creative.source;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.script.helper.UserScriptContentHash;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authorized immutable snapshot provider for an existing user script row. */
@Component
public class ScriptCreativeSourceProvider implements CreativeSourceProvider {
    @Resource private IAidComicScriptService scriptService;
    @Resource private IAidComicProjectService projectService;

    @Override
    public String sourceKind() {
        return "SCRIPT";
    }

    @Override
    public AuthorizedSource authorize(Long userId, String sourceObjectId, String requestedRevision) {
        Long scriptId;
        try { scriptId = Long.valueOf(sourceObjectId); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid script source id"); }
        AidComicScript script = scriptService.getOne(Wrappers.<AidComicScript>lambdaQuery()
                .select(AidComicScript::getId, AidComicScript::getProjectId, AidComicScript::getEpisodeId,
                        AidComicScript::getUserId, AidComicScript::getOriginalText,
                        AidComicScript::getComicVersion, AidComicScript::getStatus)
                .eq(AidComicScript::getId, scriptId)
                .eq(AidComicScript::getUserId, userId)
                .eq(AidComicScript::getDelFlag, "0")
                .last("LIMIT 1"));
        AidComicProject project = script == null ? null : projectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getId, AidComicProject::getUserId)
                .eq(AidComicProject::getId, script.getProjectId())
                .eq(AidComicProject::getUserId, userId)
                .eq(AidComicProject::getDelFlag, "0")
                .last("LIMIT 1"));
        if (script == null || project == null) throw new IllegalArgumentException("Script source is not accessible");
        String content = script.getOriginalText() == null ? "" : script.getOriginalText();
        String hash = UserScriptContentHash.calculate(content);
        String revision = revision(script, hash);
        if (StrUtil.isNotBlank(requestedRevision) && !revision.equals(requestedRevision)) {
            throw new IllegalArgumentException("Script source revision conflict");
        }
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("scriptId", String.valueOf(script.getId()));
        locator.put("projectId", String.valueOf(script.getProjectId()));
        locator.put("episodeId", String.valueOf(script.getEpisodeId()));
        locator.put("comicVersion", script.getComicVersion());
        String scope = SecureUtil.sha256("SCRIPT|" + userId + "|" + script.getProjectId()
                + "|" + script.getEpisodeId() + "|" + script.getId());
        return new AuthorizedSource(String.valueOf(script.getId()), revision, scope,
                JSON.toJSONString(locator), () -> new StringReader(content));
    }

    public static String revision(AidComicScript script, String hash) {
        return "script:" + script.getId() + ":v" + script.getComicVersion() + ":" + hash;
    }
}
