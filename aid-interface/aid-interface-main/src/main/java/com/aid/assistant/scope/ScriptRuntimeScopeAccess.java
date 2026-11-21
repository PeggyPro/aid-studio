package com.aid.assistant.scope;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.mapper.AidComicScriptMapper;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ScriptRuntimeScopeAccess implements RuntimeScopeAccess
{
    @Resource private AidComicScriptMapper scripts;

    @Override
    public boolean supports(String kind) { return "SCRIPT".equals(kind); }

    @Override
    public ResolvedContextRef resolvePinned(Long userId, AssistantRequests.ContextRef requested)
    {
        AidComicScript script = requireScript(userId, requested.getScriptId());
        String hash = SecureUtil.sha256(Objects.toString(script.getOriginalText(), ""));
        if (!Objects.equals(requested.getContentHash(), hash)
                || requested.getVersion() != null
                && !Objects.equals(requested.getVersion(), String.valueOf(script.getComicVersion())))
            stale("SCRIPT_CONTENT_CHANGED");
        AssistantRequests.ContextRef resolved = resolved(script, requested);
        verifySelection(script, resolved.getSelection());
        return new ResolvedContextRef(resolved, contentRef(script, hash), "READ");
    }

    @Override
    public ResolvedContextRef resolveCurrent(Long userId, AssistantRequests.ContextRef identity)
    {
        AidComicScript script = requireScript(userId, identity.getScriptId());
        AssistantRequests.ContextRef resolved = resolved(script, identity);
        verifySelection(script, resolved.getSelection());
        return new ResolvedContextRef(resolved,
                contentRef(script, resolved.getContentHash()), "READ");
    }

    private AidComicScript requireScript(Long userId, Long scriptId)
    {
        AidComicScript script = scripts.selectOne(new LambdaQueryWrapper<AidComicScript>()
                .eq(AidComicScript::getId, scriptId).eq(AidComicScript::getUserId, userId)
                .eq(AidComicScript::getDelFlag, "0").last("LIMIT 1"));
        if (script == null) stale("SCRIPT_UNAVAILABLE");
        return script;
    }

    private AssistantRequests.ContextRef resolved(AidComicScript script, AssistantRequests.ContextRef requested)
    {
        AssistantRequests.ContextRef result = JSON.parseObject(JSON.toJSONString(requested), AssistantRequests.ContextRef.class);
        result.setKind("SCRIPT");
        result.setScriptId(script.getId());
        result.setProjectId(script.getProjectId());
        result.setEpisodeId(script.getEpisodeId());
        result.setVersion(String.valueOf(script.getComicVersion()));
        result.setContentHash(SecureUtil.sha256(Objects.toString(script.getOriginalText(), "")));
        return result;
    }

    private void verifySelection(AidComicScript script, AssistantRequests.ScriptSelection selection)
    {
        if (selection == null) return;
        String text = Objects.toString(script.getOriginalText(), "");
        if (selection.getStart() == null || selection.getEnd() == null || selection.getStart() < 0
                || selection.getEnd() <= selection.getStart() || selection.getEnd() > text.length()
                || !Objects.equals(selection.getSelectedTextHash(),
                SecureUtil.sha256(text.substring(selection.getStart(), selection.getEnd()))))
            stale("SCRIPT_SELECTION_CHANGED");
    }

    private String contentRef(AidComicScript script, String hash)
    {
        return "script:" + script.getId() + ":" + script.getComicVersion() + ":" + hash;
    }

    private void stale(String reason)
    {
        throw new AssistantServiceException(HttpStatus.CONFLICT.value(), "ASSISTANT_CONTEXT_STALE",
                "\u811a\u672c\u4e0a\u4e0b\u6587\u5df2\u53d8\u66f4", reason);
    }
}
