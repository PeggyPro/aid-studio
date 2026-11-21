package com.aid.runtime.service;

import cn.hutool.core.util.StrUtil;
import com.aid.businessgroup.domain.AidBusinessGroup;
import com.aid.businessgroup.domain.AidBusinessGroupMember;
import com.aid.businessgroup.mapper.AidBusinessGroupMapper;
import com.aid.businessgroup.mapper.AidBusinessGroupMemberMapper;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentVersion;
import com.aid.runtime.dto.RuntimeCatalogRequests;
import com.aid.runtime.legacy.dto.LegacyRuntimeAgentRequests;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentVersionMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.vo.SkillCatalogVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Public, permission-filtered group/member catalog. Never exposes prompt or resource bodies. */
@Service
@RequiredArgsConstructor
public class RuntimeCatalogService {
    private static final String NORMAL = "0";
    private static final long MAX_MODEL_UNION_MEMBERS = 2000;
    private final AidBusinessGroupMapper groups;
    private final AidBusinessGroupMemberMapper members;
    private final AidSkillMapper skills;
    private final AidSkillVersionMapper skillVersions;
    private final AidRuntimeAgentMapper agents;
    private final AidRuntimeAgentVersionMapper agentVersions;
    private final SkillModelService modelService;

    public JSONObject groups(RuntimeCatalogRequests.GroupPage request, Long userId) {
        RuntimeCatalogRequests.GroupPage source = request == null ? new RuntimeCatalogRequests.GroupPage() : request;
        Long parentId = null;
        if (StrUtil.isNotBlank(source.getParentCode())) parentId = requireVisibleGroup(source.getParentCode(), userId).getId();
        Page<AidBusinessGroup> visible = groups.selectPage(
                new Page<>(source.getPageNum(), source.getPageSize()),
                Wrappers.<AidBusinessGroup>lambdaQuery()
                .select(AidBusinessGroup::getId, AidBusinessGroup::getGroupCode,
                        AidBusinessGroup::getParentId, AidBusinessGroup::getName,
                        AidBusinessGroup::getIconUrl, AidBusinessGroup::getDescription,
                        AidBusinessGroup::getSortOrder, AidBusinessGroup::getVisibility)
                .eq(AidBusinessGroup::getDelFlag, NORMAL).eq(AidBusinessGroup::getStatus, NORMAL)
                .eq(parentId != null, AidBusinessGroup::getParentId, parentId)
                .and(q -> q.eq(AidBusinessGroup::getVisibility, "PUBLIC")
                        .or().eq(AidBusinessGroup::getOwnerUserId, userId))
                .orderByAsc(AidBusinessGroup::getSortOrder).orderByAsc(AidBusinessGroup::getId));
        return page(visible.getRecords(), visible.getTotal());
    }

    public JSONObject memberPage(RuntimeCatalogRequests.MemberPage request, Long userId) {
        AidBusinessGroup group = requireVisibleGroup(request.getGroupCode(), userId);
        String accessible = "((member_type='SKILL' AND EXISTS (SELECT 1 FROM aid_skill s "
                + "JOIN aid_skill_version v ON v.id=s.current_version_id "
                + "WHERE s.id=aid_business_group_member.member_id AND s.status='0' "
                + "AND s.del_flag='0' AND s.invocation_scope='ENTRYPOINT' "
                + "AND (s.visibility='PUBLIC' OR s.owner_user_id={0}) "
                + "AND v.status='0' AND v.del_flag='0' AND v.executor_type='PROMPT')) "
                + "OR (member_type='AGENT' AND EXISTS (SELECT 1 FROM aid_runtime_agent a "
                + "JOIN aid_runtime_agent_version v ON v.id=a.current_version_id "
                + "WHERE a.id=aid_business_group_member.member_id AND a.agent_family='LEGACY_PLAN' "
                + "AND a.status='0' "
                + "AND a.del_flag='0' AND (a.visibility='PUBLIC' OR a.owner_user_id={0}) "
                + "AND v.status='0' AND v.del_flag='0')))";
        Page<AidBusinessGroupMember> rows = members.selectPage(
                new Page<>(request.getPageNum(), request.getPageSize()),
                Wrappers.<AidBusinessGroupMember>lambdaQuery()
                        .select(AidBusinessGroupMember::getMemberType,
                                AidBusinessGroupMember::getMemberId,
                                AidBusinessGroupMember::getSortOrder,
                                AidBusinessGroupMember::getId)
                        .eq(AidBusinessGroupMember::getGroupId, group.getId())
                        .eq(AidBusinessGroupMember::getDelFlag, NORMAL)
                        .apply(accessible, userId)
                        .orderByAsc(AidBusinessGroupMember::getSortOrder)
                        .orderByAsc(AidBusinessGroupMember::getId));
        return page(visibleMembers(group, userId, rows.getRecords()), rows.getTotal());
    }

    public JSONObject modelPage(RuntimeCatalogRequests.ModelPage request, Long userId) {
        AidBusinessGroup group = requireVisibleGroup(request.getGroupCode(), userId);
        long memberCount = members.selectCount(Wrappers.<AidBusinessGroupMember>lambdaQuery()
                .eq(AidBusinessGroupMember::getGroupId, group.getId())
                .eq(AidBusinessGroupMember::getDelFlag, NORMAL));
        if (memberCount > MAX_MODEL_UNION_MEMBERS)
            throw new ServiceException("分组成员超过模型并集查询上限，请拆分子分组", 409);
        List<JSONObject> visible = visibleMembers(group, userId);
        Map<String, JSONObject> union = new LinkedHashMap<>();
        for (JSONObject member : visible) {
            String code = member.getString("memberCode");
            for (SkillCatalogVO.ModelItem model : member.getList("models", SkillCatalogVO.ModelItem.class)) {
                JSONObject entry = union.computeIfAbsent(model.getModelCode(), ignored -> {
                    JSONObject item = new JSONObject();
                    item.put("model", model);
                    item.put("memberCodes", new ArrayList<String>());
                    return item;
                });
                @SuppressWarnings("unchecked")
                List<String> codes = (List<String>) entry.get("memberCodes");
                codes.add(code);
            }
        }
        return page(new ArrayList<>(union.values()), request.getPageNum(), request.getPageSize());
    }

    private AidBusinessGroup requireVisibleGroup(String code, Long userId) {
        AidBusinessGroup group = groups.selectOne(Wrappers.<AidBusinessGroup>lambdaQuery()
                .eq(AidBusinessGroup::getGroupCode, code)
                .eq(AidBusinessGroup::getDelFlag, NORMAL)
                .eq(AidBusinessGroup::getStatus, NORMAL).last("limit 1"));
        if (group == null || !("PUBLIC".equals(group.getVisibility())
                || Objects.equals(group.getOwnerUserId(), userId)))
            throw new ServiceException("业务分组不可用");
        return group;
    }

    private List<JSONObject> visibleMembers(AidBusinessGroup group, Long userId) {
        List<AidBusinessGroupMember> rows = members.selectList(Wrappers.<AidBusinessGroupMember>lambdaQuery()
                .select(AidBusinessGroupMember::getMemberType, AidBusinessGroupMember::getMemberId,
                        AidBusinessGroupMember::getSortOrder, AidBusinessGroupMember::getId)
                .eq(AidBusinessGroupMember::getGroupId, group.getId())
                .eq(AidBusinessGroupMember::getDelFlag, NORMAL)
                .orderByAsc(AidBusinessGroupMember::getSortOrder).orderByAsc(AidBusinessGroupMember::getId));
        return visibleMembers(group, userId, rows);
    }

    private List<JSONObject> visibleMembers(AidBusinessGroup group, Long userId,
                                            List<AidBusinessGroupMember> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, AidSkill> skillMap = loadSkills(rows, userId);
        Map<Long, AidRuntimeAgent> agentMap = loadAgents(rows, userId);
        Map<Long, AidSkillVersion> skillVersionMap = loadSkillVersions(skillMap.values());
        Map<Long, AidRuntimeAgentVersion> agentVersionMap = loadAgentVersions(agentMap.values());
        Map<Long, SkillModelService.CatalogProjection> skillModels = projections(skillVersionMap);
        Map<Long, SkillModelService.CatalogProjection> agentModels = agentProjections(agentVersionMap);
        List<JSONObject> result = new ArrayList<>();
        for (AidBusinessGroupMember row : rows) {
            JSONObject item = new JSONObject();
            if ("SKILL".equals(row.getMemberType())) {
                AidSkill skill = skillMap.get(row.getMemberId());
                if (skill == null) continue;
                AidSkillVersion version = skillVersionMap.get(skill.getId());
                SkillModelService.CatalogProjection projection = skillModels.get(skill.getId());
                if (version == null || projection == null) continue;
                item.put("memberCode", skill.getSkillCode());
                item.put("name", skill.getName());
                item.put("description", skill.getDescription());
                item.put("iconUrl", skill.getIconUrl());
                item.put("outputKind", SkillModelService.outputKind(version));
                item.put("versionCode", version.getVersionCode());
                item.put("versionId", version.getId());
                item.put("defaultModelCode", projection.defaultModelCode());
                item.put("models", projection.models());
            } else {
                AidRuntimeAgent agent = agentMap.get(row.getMemberId());
                if (agent == null) continue;
                AidRuntimeAgentVersion version = agentVersionMap.get(agent.getId());
                SkillModelService.CatalogProjection projection = agentModels.get(agent.getId());
                if (version == null || projection == null) continue;
                item.put("memberCode", agent.getAgentCode());
                item.put("name", agent.getName());
                item.put("description", agent.getDescription());
                item.put("iconUrl", agent.getIconUrl());
                item.put("outputKind", JSON.parseObject(version.getDefinitionJson())
                        .getString("outputKind"));
                item.put("versionCode", version.getVersionCode());
                item.put("versionId", version.getId());
                item.put("defaultModelCode", projection.defaultModelCode());
                item.put("models", projection.models());
            }
            item.put("memberType", row.getMemberType());
            item.put("memberId", row.getMemberId());
            item.put("groupCode", group.getGroupCode());
            item.put("sortOrder", row.getSortOrder());
            result.add(item);
        }
        return result;
    }

    private Map<Long, AidSkill> loadSkills(List<AidBusinessGroupMember> rows, Long userId) {
        List<Long> ids = rows.stream().filter(row -> "SKILL".equals(row.getMemberType()))
                .map(AidBusinessGroupMember::getMemberId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return skills.selectList(Wrappers.<AidSkill>lambdaQuery()
                .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                        AidSkill::getDescription, AidSkill::getIconUrl,
                        AidSkill::getVisibility, AidSkill::getOwnerUserId,
                        AidSkill::getCurrentVersionId)
                .in(AidSkill::getId, ids)
                .eq(AidSkill::getInvocationScope, "ENTRYPOINT")
                .eq(AidSkill::getStatus, NORMAL).eq(AidSkill::getDelFlag, NORMAL)
                .isNotNull(AidSkill::getCurrentVersionId)
                .and(q -> q.eq(AidSkill::getVisibility, "PUBLIC").or().eq(AidSkill::getOwnerUserId, userId)))
                .stream().collect(Collectors.toMap(AidSkill::getId, Function.identity()));
    }

    private Map<Long, AidRuntimeAgent> loadAgents(List<AidBusinessGroupMember> rows, Long userId) {
        List<Long> ids = rows.stream().filter(row -> "AGENT".equals(row.getMemberType()))
                .map(AidBusinessGroupMember::getMemberId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return agents.selectList(Wrappers.<AidRuntimeAgent>lambdaQuery()
                .select(AidRuntimeAgent::getId, AidRuntimeAgent::getAgentCode,
                        AidRuntimeAgent::getName, AidRuntimeAgent::getDescription,
                        AidRuntimeAgent::getIconUrl, AidRuntimeAgent::getVisibility,
                        AidRuntimeAgent::getOwnerUserId, AidRuntimeAgent::getAgentFamily,
                        AidRuntimeAgent::getCurrentVersionId)
                .in(AidRuntimeAgent::getId, ids)
                .eq(AidRuntimeAgent::getAgentFamily, LegacyRuntimeAgentRequests.LEGACY_PLAN)
                .eq(AidRuntimeAgent::getStatus, NORMAL).eq(AidRuntimeAgent::getDelFlag, NORMAL)
                .isNotNull(AidRuntimeAgent::getCurrentVersionId)
                .and(q -> q.eq(AidRuntimeAgent::getVisibility, "PUBLIC")
                        .or().eq(AidRuntimeAgent::getOwnerUserId, userId)))
                .stream().collect(Collectors.toMap(AidRuntimeAgent::getId, Function.identity()));
    }

    private Map<Long, AidSkillVersion> loadSkillVersions(java.util.Collection<AidSkill> identities) {
        Set<Long> ids = identities.stream().map(AidSkill::getCurrentVersionId).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, AidSkillVersion> byId = skillVersions.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                .select(AidSkillVersion::getId, AidSkillVersion::getSkillId,
                        AidSkillVersion::getVersionCode, AidSkillVersion::getModelCode,
                        AidSkillVersion::getModelConfigJson, AidSkillVersion::getDefinitionJson,
                        AidSkillVersion::getExecutorType)
                .in(AidSkillVersion::getId, ids)
                .eq(AidSkillVersion::getStatus, NORMAL).eq(AidSkillVersion::getDelFlag, NORMAL))
                .stream().filter(version -> "PROMPT".equals(version.getExecutorType()))
                .collect(Collectors.toMap(AidSkillVersion::getId, Function.identity()));
        return identities.stream().filter(skill -> byId.containsKey(skill.getCurrentVersionId()))
                .collect(Collectors.toMap(AidSkill::getId, skill -> byId.get(skill.getCurrentVersionId())));
    }

    private Map<Long, AidRuntimeAgentVersion> loadAgentVersions(java.util.Collection<AidRuntimeAgent> identities) {
        Set<Long> ids = identities.stream().map(AidRuntimeAgent::getCurrentVersionId).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, AidRuntimeAgentVersion> byId = agentVersions.selectList(
                Wrappers.<AidRuntimeAgentVersion>lambdaQuery()
                        .select(AidRuntimeAgentVersion::getId, AidRuntimeAgentVersion::getAgentId,
                                AidRuntimeAgentVersion::getVersionCode,
                                AidRuntimeAgentVersion::getModelConfigJson,
                                AidRuntimeAgentVersion::getDefinitionJson)
                        .in(AidRuntimeAgentVersion::getId, ids)
                        .eq(AidRuntimeAgentVersion::getStatus, NORMAL)
                        .eq(AidRuntimeAgentVersion::getDelFlag, NORMAL))
                .stream().filter(RuntimeCatalogService::isLegacyDefinition)
                .collect(Collectors.toMap(AidRuntimeAgentVersion::getId, Function.identity()));
        return identities.stream().filter(agent -> byId.containsKey(agent.getCurrentVersionId()))
                .collect(Collectors.toMap(AidRuntimeAgent::getId, agent -> byId.get(agent.getCurrentVersionId())));
    }

    private Map<Long, SkillModelService.CatalogProjection> projections(Map<Long, AidSkillVersion> versions) {
        if (versions.isEmpty()) return Map.of();
        Map<Long, SkillModelService.CatalogProjection> result = new LinkedHashMap<>();
        List<Map.Entry<Long, AidSkillVersion>> entries = new ArrayList<>(versions.entrySet());
        for (int i = 0; i < entries.size(); i += 100) {
            Map<Long, AidSkillVersion> batch = entries.subList(i, Math.min(i + 100, entries.size()))
                    .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            result.putAll(modelService.catalogProjections(batch));
        }
        return result;
    }

    private static boolean isLegacyDefinition(AidRuntimeAgentVersion version) {
        try {
            JSONObject definition = JSON.parseObject(version.getDefinitionJson());
            String executorType = definition == null ? null : definition.getString("executorType");
            return Set.of("MODEL_PLAN", "SKILL_ORCHESTRATOR").contains(executorType)
                    && StrUtil.isBlank(definition.getString("protocolVersion"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private Map<Long, SkillModelService.CatalogProjection> agentProjections(
            Map<Long, AidRuntimeAgentVersion> versions) {
        Map<Long, AidSkillVersion> pseudo = new LinkedHashMap<>();
        versions.forEach((id, version) -> {
            AidSkillVersion value = new AidSkillVersion();
            value.setModelConfigJson(version.getModelConfigJson());
            value.setDefinitionJson(version.getDefinitionJson());
            pseudo.put(id, value);
        });
        return projections(pseudo);
    }

    private static <T> JSONObject page(List<T> source, int pageNum, int pageSize) {
        int from = (int) Math.min(source.size(), (long) (pageNum - 1) * pageSize);
        int to = Math.min(source.size(), from + pageSize);
        JSONObject result = new JSONObject();
        result.put("total", source.size());
        result.put("data", source.subList(from, to));
        return result;
    }

    private static <T> JSONObject page(List<T> records, long total) {
        JSONObject result = new JSONObject();
        result.put("total", total);
        result.put("data", records);
        return result;
    }
}
