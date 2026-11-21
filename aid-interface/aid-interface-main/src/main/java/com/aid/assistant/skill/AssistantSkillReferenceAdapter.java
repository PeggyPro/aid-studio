package com.aid.assistant.skill;

import cn.hutool.core.util.StrUtil;
import com.aid.skill.dto.SkillInvocationRequests;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Maps authoritative assistant reference content into the existing Skill reference contract. */
public final class AssistantSkillReferenceAdapter {
    private static final int MAX_REFERENCES = 20;
    private static final int MAX_TEXT_CHARS = 20_000;

    private AssistantSkillReferenceAdapter() { }

    public static List<SkillInvocationRequests.ReferenceItem> adapt(JSONArray contents) {
        if (contents == null || contents.isEmpty()) return List.of();
        List<SkillInvocationRequests.ReferenceItem> result = new ArrayList<>();
        int totalChars = 0;
        for (Object raw : contents) {
            if (!(raw instanceof JSONObject content)) {
                throw rejected("SKILL_REFERENCE_CONTEXT_INVALID");
            }
            Integer ordinal = content.getInteger("ordinal");
            if (ordinal == null || ordinal < 0) throw rejected("SKILL_REFERENCE_CONTEXT_INVALID");
            // The parent reads this workspace summary as context. A child Skill receives the
            // actual node and attachment references, with their existing content checks.
            if ("CURRENT_WORKSPACE_NODES".equals(content.getString("semanticSource"))
                    && content.get("objectId") == null) continue;
            String text = semanticText(content);
            if (StrUtil.isBlank(text)) {
                if (Boolean.TRUE.equals(content.getBoolean("semanticDescriptionRequired"))
                        || "MEDIA_REQUIRES_MODEL_OR_DESCRIPTION".equals(
                            content.getString("semanticSource"))) {
                    throw rejected("SKILL_REFERENCE_DESCRIPTION_REQUIRED");
                }
                throw rejected("SKILL_REFERENCE_MEDIA_UNSUPPORTED");
            }
            if (result.size() >= MAX_REFERENCES) {
                throw rejected("SKILL_REFERENCE_COUNT_EXCEEDED");
            }
            totalChars += text.length();
            if (text.length() > MAX_TEXT_CHARS || totalChars > MAX_TEXT_CHARS) {
                throw rejected("SKILL_REFERENCE_CONTEXT_TOO_LARGE");
            }
            SkillInvocationRequests.ReferenceItem reference = new SkillInvocationRequests.ReferenceItem();
            reference.setReferenceType("TEXT");
            reference.setText(text);
            reference.setSelectionId("assistant-ref-" + ordinal);
            result.add(reference);
        }
        return List.copyOf(result);
    }

    private static String semanticText(JSONObject content) {
        String text = content.getString("text");
        if (StrUtil.isNotBlank(text)) return text;
        text = content.getString("textContent");
        if (StrUtil.isNotBlank(text)) return text;
        String prompt = content.getString("prompt");
        if (StrUtil.isBlank(prompt)) return null;
        return "作者对参考媒体的文字说明（不代表媒体视觉内容）：" + prompt;
    }

    private static AssistantSkillPreparationRejectedException rejected(String code) {
        return new AssistantSkillPreparationRejectedException(code, new IllegalArgumentException(code));
    }
}
