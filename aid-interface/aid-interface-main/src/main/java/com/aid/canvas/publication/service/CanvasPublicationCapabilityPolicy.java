package com.aid.canvas.publication.service;

import com.aid.aid.domain.AidAgent;
import com.aid.aid.mapper.AidAgentMapper;
import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** 发布只携带可公开解析的能力编码，不携带提示词、账户授权或执行身份。 */
@Service
@RequiredArgsConstructor
public class CanvasPublicationCapabilityPolicy {
    private final AidAgentMapper agents;
    private final AidSkillMapper skills;
    private final AidSkillVersionMapper versions;

    public void requirePortable(JSONArray objects) {
        Map<String, String> agentNodes = new LinkedHashMap<>(), skillNodes = new LinkedHashMap<>();
        for (Object raw : objects) {
            JSONObject object = (JSONObject) raw;
            if (!"NODE".equals(object.getString("objectType"))) continue;
            JSONObject content = object.getJSONObject("content"), metadata = content.getJSONObject("metadata");
            JSONObject studio = metadata == null ? null : metadata.getJSONObject("studioData");
            if (studio == null) continue;
            String title = Objects.toString(content.getString("title"), object.getString("objectId"));
            reference(studio, "composerAgentCode", title, agentNodes);
            reference(studio, "composerSkillId", title, skillNodes);
        }
        Set<String> publicAgents = agentNodes.isEmpty() ? Set.of() : agents.selectList(Wrappers.<AidAgent>lambdaQuery()
                .select(AidAgent::getAgentCode).in(AidAgent::getAgentCode, agentNodes.keySet())
                .eq(AidAgent::getStatus, 1).eq(AidAgent::getDelFlag, "0")).stream()
                .map(AidAgent::getAgentCode).collect(Collectors.toSet());
        for (var entry : agentNodes.entrySet()) if (!publicAgents.contains(entry.getKey()))
            unavailable(entry.getValue(), "智能体", entry.getKey());
        if (skillNodes.isEmpty()) return;
        List<AidSkill> identities = skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getCurrentVersionId)
                .in(AidSkill::getSkillCode, skillNodes.keySet()).eq(AidSkill::getVisibility, "PUBLIC")
                .eq(AidSkill::getInvocationScope, "ENTRYPOINT").eq(AidSkill::getStatus, "0")
                .eq(AidSkill::getDelFlag, "0").isNotNull(AidSkill::getCurrentVersionId));
        Set<Long> currentIds = identities.stream().map(AidSkill::getCurrentVersionId).collect(Collectors.toSet());
        Map<Long, Long> publicVersions = currentIds.isEmpty() ? Map.of() : versions.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                .select(AidSkillVersion::getId, AidSkillVersion::getSkillId).in(AidSkillVersion::getId, currentIds)
                .eq(AidSkillVersion::getVisibility, "PUBLIC").eq(AidSkillVersion::getInvocationScope, "ENTRYPOINT")
                .eq(AidSkillVersion::getPublishStatus, "PUBLISHED").eq(AidSkillVersion::getStatus, "0")
                .eq(AidSkillVersion::getDelFlag, "0")).stream()
                .collect(Collectors.toMap(AidSkillVersion::getId, AidSkillVersion::getSkillId));
        Set<String> publicSkills = identities.stream()
                .filter(skill -> Objects.equals(skill.getId(), publicVersions.get(skill.getCurrentVersionId())))
                .map(AidSkill::getSkillCode).collect(Collectors.toSet());
        for (var entry : skillNodes.entrySet()) if (!publicSkills.contains(entry.getKey()))
            unavailable(entry.getValue(), "Skill", entry.getKey());
    }

    private static void reference(JSONObject studio, String key, String title, Map<String, String> references) {
        Object value = studio.get(key);
        if (value == null || value instanceof String text && text.isBlank()) return;
        if (!(value instanceof String code)) throw new ServiceException("节点「" + title + "」的能力编码格式错误，请重新选择能力后提交", 400);
        references.putIfAbsent(code, title);
    }

    private static void unavailable(String title, String kind, String code) {
        throw new ServiceException("节点「" + title + "」引用的" + kind + "「" + code
                + "」未公开、已停用或不存在，请更换为公开可用的能力后重新提交；不会公开私有提示词或账户权限", 400);
    }
}
