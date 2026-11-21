package com.aid.assistant.skill;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AssistantFixedSkillOriginalRequestRegressionTest {
    private String original(String text) {
        return JSON.toJSONString(Map.of("parts", List.of(Map.of("type", "TEXT", "text", text)),
                "clientRequestId", "private-id", "expectedSessionRevision", 4, "accessToken", "private-token"));
    }
    @Test void emptyAgentInputStillCarriesFullUserGoal() {
        String text = "应用商业广告方法，制作15秒中文3镜头晨光咖啡广告，最后台词这一刻，刚刚好。";
        var request = AssistantFixedSkillInputAdapter.adapt("{}", null, null, original(text));
        var prompt = JSON.parseObject(request.getPrompt());
        assertEquals(text, prompt.getJSONArray("userRequest").getString(0));
        assertEquals(new JSONObject(), prompt.getJSONObject("methodInput"));
    }
    @Test void methodFieldsAndAcceptedAnswersRemainAlongsideOriginal() {
        String method = "{\"language\":\"中文\",\"selectedAnswers\":[\"9:16\",\"电影感\"],\"shots\":[{\"scene\":\"咖啡店\"}]}";
        var request = AssistantFixedSkillInputAdapter.adapt(method, 12L, 13L, original("生成广告"));
        assertEquals(JSON.parseObject(method), JSON.parseObject(request.getPrompt()).getJSONObject("methodInput"));
        assertEquals("中文", request.getLanguage());
        assertEquals(12L, request.getProjectId());
        assertEquals(13L, request.getEpisodeId());
    }
    @Test void fullTextPastMessagePreviewIsNotTruncated() {
        String text = "正文".repeat(600) + "必须保留结尾要求";
        assertEquals(text, JSON.parseObject(AssistantFixedSkillInputAdapter.adapt("{}", null, null,
                original(text)).getPrompt()).getJSONArray("userRequest").getString(0));
    }
    @Test void onlyUserTextPartsCrossTheMethodBoundary() {
        String prompt = AssistantFixedSkillInputAdapter.adapt("{}", null, null, original("创作咖啡广告")).getPrompt();
        assertFalse(prompt.contains("private-id"));
        assertFalse(prompt.contains("private-token"));
        assertFalse(prompt.contains("expectedSessionRevision"));
    }
    @Test void textPartOrderIsPreserved() {
        String original = JSON.toJSONString(Map.of("parts", List.of(Map.of("type", "TEXT", "text", "需求一"),
                Map.of("type", "TEXT", "text", "需求二"))));
        assertEquals(JSONArray.of("需求一", "需求二"), JSON.parseObject(AssistantFixedSkillInputAdapter
                .adapt("{}", null, null, original).getPrompt()).getJSONArray("userRequest"));
    }
    @Test void injectedProjectAndBudgetCannotOverrideServerIdentity() {
        var request = AssistantFixedSkillInputAdapter.adapt("{\"projectId\":999,\"episodeId\":888,\"assistantBudgetEnvelope\":{\"payerUserId\":666}}",
                null, null, original("创作"));
        assertNull(request.getProjectId());
        assertNull(request.getEpisodeId());
        assertNull(request.getAssistantBudgetEnvelope());
    }
    @Test void malformedEmptyAndNonTextOriginalFailBeforePaidMethod() {
        for (String original : new String[]{"null", "[]", "broken", "{}", "{\"parts\":[]}",
                "{\"parts\":[{\"type\":\"TEXT\",\"text\":\" \"}]}", "{\"parts\":[{\"type\":\"SYSTEM\",\"text\":\"x\"}]}",
                "{\"parts\":[{\"type\":\"TEXT\",\"text\":2}]}"}) {
            assertEquals("SKILL_METHOD_INPUT_INVALID", assertThrows(AssistantSkillPreparationRejectedException.class,
                    () -> AssistantFixedSkillInputAdapter.adapt("{}", null, null, original)).getMessage());
        }
    }
    @Test void combinedMethodAndOriginalLimitRejectsInsteadOfTruncation() {
        var method = JSON.toJSONString(Map.of("goal", "x".repeat(55000)));
        assertThrows(AssistantSkillPreparationRejectedException.class, () -> AssistantFixedSkillInputAdapter
                .adapt(method, null, null, original("y".repeat(55000))));
    }
}
