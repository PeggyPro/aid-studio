package com.aid.businessgroup.service;

import cn.hutool.core.util.StrUtil;
import com.aid.businessgroup.domain.AidBusinessGroup;
import com.aid.businessgroup.domain.AidBusinessGroupMember;
import com.aid.businessgroup.dto.BusinessGroupRequests;
import com.aid.businessgroup.mapper.AidBusinessGroupMapper;
import com.aid.businessgroup.mapper.AidBusinessGroupMemberMapper;
import com.aid.common.exception.ServiceException;
import com.aid.common.core.domain.ApiErrorDetails.DependencyReference;
import com.aid.runtime.service.RuntimeReferenceErrors;
import com.aid.runtime.domain.AidRuntimeAgent;
import com.aid.runtime.domain.AidRuntimeAgentVersion;
import com.aid.runtime.legacy.dto.LegacyRuntimeAgentRequests;
import com.aid.runtime.mapper.AidRuntimeAgentMapper;
import com.aid.runtime.mapper.AidRuntimeAgentVersionMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BusinessGroupService {
    private static final String NORMAL = "0";
    private final AidBusinessGroupMapper groupMapper;
    private final AidBusinessGroupMemberMapper memberMapper;
    private final AidSkillMapper skillMapper;
    private final AidRuntimeAgentMapper agentMapper;
    private final AidSkillVersionMapper skillVersionMapper;
    private final AidRuntimeAgentVersionMapper agentVersionMapper;

    public Page<AidBusinessGroup> page(BusinessGroupRequests.Page request) {
        BusinessGroupRequests.Page source = request == null ? new BusinessGroupRequests.Page() : request;
        Long parentId = null;
        if (StrUtil.isNotBlank(source.getParentCode())) {
            parentId = requireGroupCode(source.getParentCode()).getId();
        }
        return groupMapper.selectPage(new Page<>(source.getPageNum(), source.getPageSize()),
                Wrappers.<AidBusinessGroup>lambdaQuery()
                        .select(AidBusinessGroup::getId, AidBusinessGroup::getGroupCode,
                                AidBusinessGroup::getParentId, AidBusinessGroup::getName,
                                AidBusinessGroup::getIconUrl, AidBusinessGroup::getDescription,
                                AidBusinessGroup::getSortOrder, AidBusinessGroup::getVisibility,
                                AidBusinessGroup::getStatus, AidBusinessGroup::getOwnerUserId)
                        .eq(AidBusinessGroup::getDelFlag, NORMAL)
                        .eq(parentId != null, AidBusinessGroup::getParentId, parentId)
                        .eq(Boolean.TRUE.equals(source.getEnabledOnly()), AidBusinessGroup::getStatus, NORMAL)
                        .and(StrUtil.isNotBlank(source.getKeyword()), q -> q
                                .like(AidBusinessGroup::getName, source.getKeyword())
                                .or().like(AidBusinessGroup::getGroupCode, source.getKeyword()))
                        .orderByAsc(AidBusinessGroup::getSortOrder).orderByAsc(AidBusinessGroup::getId));
    }

    public AidBusinessGroup requireGroupCode(String groupCode) {
        AidBusinessGroup group = groupMapper.selectOne(Wrappers.<AidBusinessGroup>lambdaQuery()
                .eq(AidBusinessGroup::getGroupCode, groupCode)
                .eq(AidBusinessGroup::getDelFlag, NORMAL).last("limit 1"));
        if (group == null) throw new ServiceException("业务分组不存在");
        return group;
    }

    public AidBusinessGroup requireGroup(Long id) {
        AidBusinessGroup group = groupMapper.selectById(id);
        if (group == null || !NORMAL.equals(group.getDelFlag())) throw new ServiceException("业务分组不存在");
        return group;
    }

    @Transactional(rollbackFor = Exception.class)
    public AidBusinessGroup save(BusinessGroupRequests.Save request, Long operatorId, String operator) {
        AidBusinessGroup existing = request.getId() == null ? null : groupMapper.selectOne(
                Wrappers.<AidBusinessGroup>lambdaQuery().eq(AidBusinessGroup::getId, request.getId())
                        .eq(AidBusinessGroup::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (request.getId() != null && existing == null) throw new ServiceException("业务分组不存在");
        if (existing != null && !Objects.equals(existing.getGroupCode(), request.getGroupCode())) {
            throw new ServiceException("业务分组编码不可修改");
        }
        if (request.getParentId() != null) {
            if (Objects.equals(request.getParentId(), request.getId())) throw new ServiceException("分组不能以自身为父级");
            AidBusinessGroup parent = requireGroup(request.getParentId());
            if (existing != null) {
                Long ancestorId = parent.getId();
                for (int depth = 0; ancestorId != null && depth < 32; depth++) {
                    if (Objects.equals(ancestorId, existing.getId())) throw new ServiceException("分组层级不能形成环");
                    AidBusinessGroup ancestor = groupMapper.selectById(ancestorId);
                    ancestorId = ancestor == null ? null : ancestor.getParentId();
                    if (depth == 31 && ancestorId != null) throw new ServiceException("分组层级过深");
                }
            }
        }
        if (existing != null && NORMAL.equals(existing.getStatus()) && !NORMAL.equals(request.getStatus())) {
            // Active members are removed explicitly; a disabled group may retain bindings for later re-enable.
        }
        AidBusinessGroup value = existing == null ? new AidBusinessGroup() : existing;
        value.setGroupCode(request.getGroupCode());
        value.setParentId(request.getParentId());
        value.setName(StrUtil.trim(request.getName()));
        value.setIconUrl(StrUtil.trim(request.getIconUrl()));
        value.setDescription(StrUtil.trim(request.getDescription()));
        value.setSortOrder(request.getSortOrder());
        value.setVisibility(request.getVisibility());
        value.setStatus(request.getStatus());
        value.setDelFlag(NORMAL);
        value.setUpdateBy(operator);
        value.setUpdateTime(new Date());
        if (existing == null) {
            value.setOwnerUserId(operatorId);
            value.setCreateBy(operator);
            value.setCreateTime(new Date());
            try { groupMapper.insert(value); }
            catch (DuplicateKeyException error) { throw new ServiceException("业务分组编码已存在"); }
        } else if (groupMapper.updateById(value) != 1) {
            throw new ServiceException("业务分组已变化");
        }
        return value;
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id, String operator) {
        AidBusinessGroup group = groupMapper.selectOne(Wrappers.<AidBusinessGroup>lambdaQuery()
                .eq(AidBusinessGroup::getId, id).eq(AidBusinessGroup::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (group == null) throw new ServiceException("业务分组不存在");
        long children = groupMapper.selectCount(Wrappers.<AidBusinessGroup>lambdaQuery()
                .eq(AidBusinessGroup::getParentId, id).eq(AidBusinessGroup::getDelFlag, NORMAL));
        long members = memberMapper.selectCount(Wrappers.<AidBusinessGroupMember>lambdaQuery()
                .eq(AidBusinessGroupMember::getGroupId, id).eq(AidBusinessGroupMember::getDelFlag, NORMAL));
        if (children > 0 || members > 0) {
            List<DependencyReference> references = new ArrayList<>();
            for (AidBusinessGroup child : groupMapper.selectList(Wrappers.<AidBusinessGroup>lambdaQuery()
                    .select(AidBusinessGroup::getId, AidBusinessGroup::getGroupCode,
                            AidBusinessGroup::getName)
                    .eq(AidBusinessGroup::getParentId, id).eq(AidBusinessGroup::getDelFlag, NORMAL)
                    .orderByAsc(AidBusinessGroup::getId).last("limit 50"))) {
                references.add(RuntimeReferenceErrors.reference("BUSINESS_GROUP", child.getId(),
                        child.getGroupCode(), child.getName(), null, "PARENT_GROUP",
                        "aid/businessGroup/index"));
            }
            int remaining = 50 - references.size();
            if (remaining > 0) for (AidBusinessGroupMember member : memberMapper.selectList(
                    Wrappers.<AidBusinessGroupMember>lambdaQuery()
                            .eq(AidBusinessGroupMember::getGroupId, id)
                            .eq(AidBusinessGroupMember::getDelFlag, NORMAL)
                            .orderByAsc(AidBusinessGroupMember::getId)
                            .last("limit " + remaining))) {
                if ("SKILL".equals(member.getMemberType())) {
                    AidSkill skill = skillMapper.selectById(member.getMemberId());
                    references.add(RuntimeReferenceErrors.reference("SKILL", member.getMemberId(),
                            skill == null ? null : skill.getSkillCode(),
                            skill == null ? null : skill.getName(), null, "GROUP_MEMBER",
                            "aid/businessGroup/index"));
                } else {
                    AidRuntimeAgent agent = agentMapper.selectById(member.getMemberId());
                    references.add(RuntimeReferenceErrors.reference("AGENT", member.getMemberId(),
                            agent == null ? null : agent.getAgentCode(),
                            agent == null ? null : agent.getName(), null, "GROUP_MEMBER",
                            "aid/businessGroup/index"));
                }
            }
            RuntimeReferenceErrors.reject(references, children + members);
        }
        group.setDelFlag("1");
        group.setStatus("1");
        group.setUpdateBy(operator);
        group.setUpdateTime(new Date());
        groupMapper.updateById(group);
    }

    public Page<JSONObject> members(BusinessGroupRequests.MemberPage request) {
        requireGroup(request.getGroupId());
        Page<AidBusinessGroupMember> rows = memberMapper.selectPage(
                new Page<>(request.getPageNum(), request.getPageSize()),
                Wrappers.<AidBusinessGroupMember>lambdaQuery()
                        .eq(AidBusinessGroupMember::getGroupId, request.getGroupId())
                        .eq(AidBusinessGroupMember::getDelFlag, NORMAL)
                        .orderByAsc(AidBusinessGroupMember::getSortOrder)
                        .orderByAsc(AidBusinessGroupMember::getId));
        List<Long> skillIds = rows.getRecords().stream().filter(row -> "SKILL".equals(row.getMemberType()))
                .map(AidBusinessGroupMember::getMemberId).distinct().toList();
        List<Long> agentIds = rows.getRecords().stream().filter(row -> "AGENT".equals(row.getMemberType()))
                .map(AidBusinessGroupMember::getMemberId).distinct().toList();
        Map<Long, AidSkill> skills = skillIds.isEmpty() ? Map.of()
                : skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getName,
                                AidSkill::getStatus, AidSkill::getCurrentVersionId)
                        .in(AidSkill::getId, skillIds)).stream()
                        .collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        Map<Long, AidRuntimeAgent> agents = agentIds.isEmpty() ? Map.of()
                : agentMapper.selectList(Wrappers.<AidRuntimeAgent>lambdaQuery()
                        .select(AidRuntimeAgent::getId, AidRuntimeAgent::getAgentCode,
                                AidRuntimeAgent::getName, AidRuntimeAgent::getStatus,
                                AidRuntimeAgent::getCurrentVersionId)
                        .in(AidRuntimeAgent::getId, agentIds)).stream()
                        .collect(Collectors.toMap(AidRuntimeAgent::getId, Function.identity()));
        Set<Long> skillVersionIds = skills.values().stream().map(AidSkill::getCurrentVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> agentVersionIds = agents.values().stream().map(AidRuntimeAgent::getCurrentVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> skillVersionCodes = skillVersionIds.isEmpty() ? Map.of()
                : skillVersionMapper.selectList(Wrappers.<AidSkillVersion>lambdaQuery()
                        .select(AidSkillVersion::getId, AidSkillVersion::getVersionCode)
                        .in(AidSkillVersion::getId, skillVersionIds)).stream()
                        .collect(Collectors.toMap(AidSkillVersion::getId, AidSkillVersion::getVersionCode));
        Map<Long, String> agentVersionCodes = agentVersionIds.isEmpty() ? Map.of()
                : agentVersionMapper.selectList(Wrappers.<AidRuntimeAgentVersion>lambdaQuery()
                        .select(AidRuntimeAgentVersion::getId, AidRuntimeAgentVersion::getVersionCode)
                        .in(AidRuntimeAgentVersion::getId, agentVersionIds)).stream()
                        .collect(Collectors.toMap(AidRuntimeAgentVersion::getId,
                                AidRuntimeAgentVersion::getVersionCode));
        Page<JSONObject> result = new Page<>(request.getPageNum(), request.getPageSize(), rows.getTotal());
        result.setRecords(rows.getRecords().stream().map(row -> {
            JSONObject item = JSON.parseObject(JSON.toJSONString(row));
            if ("SKILL".equals(row.getMemberType())) {
                AidSkill skill = skills.get(row.getMemberId());
                item.put("memberName", skill == null ? null : skill.getName());
                item.put("memberCode", skill == null ? null : skill.getSkillCode());
                item.put("memberStatus", skill == null ? "MISSING" : skill.getStatus());
                item.put("currentVersionCode", skill == null ? null
                        : skillVersionCodes.get(skill.getCurrentVersionId()));
            } else {
                AidRuntimeAgent agent = agents.get(row.getMemberId());
                item.put("memberName", agent == null ? null : agent.getName());
                item.put("memberCode", agent == null ? null : agent.getAgentCode());
                item.put("memberStatus", agent == null ? "MISSING" : agent.getStatus());
                item.put("currentVersionCode", agent == null ? null
                        : agentVersionCodes.get(agent.getCurrentVersionId()));
            }
            return item;
        }).toList());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public AidBusinessGroupMember saveMember(BusinessGroupRequests.MemberSave request, String operator) {
        AidBusinessGroup writableGroup = groupMapper.selectOne(Wrappers.<AidBusinessGroup>lambdaQuery()
                .eq(AidBusinessGroup::getId, request.getGroupId())
                .eq(AidBusinessGroup::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (writableGroup == null) throw new ServiceException("业务分组不存在");
        if ("SKILL".equals(request.getMemberType())) {
            AidSkill skill = skillMapper.selectOne(Wrappers.<AidSkill>lambdaQuery()
                    .eq(AidSkill::getId, request.getMemberId()).last("FOR UPDATE"));
            if (skill == null || !NORMAL.equals(skill.getDelFlag())) throw new ServiceException("Skill不存在");
            if (skill.getCurrentVersionId() != null) {
                AidSkillVersion version = skillVersionMapper.selectById(skill.getCurrentVersionId());
                if (version != null) assertGroupSchema(request.getGroupId(), skill.getId(),
                        version.getInputSchemaJson(), version.getOutputSchemaJson());
            }
        } else {
            AidRuntimeAgent agent = agentMapper.selectOne(Wrappers.<AidRuntimeAgent>lambdaQuery()
                    .eq(AidRuntimeAgent::getId, request.getMemberId()).last("FOR UPDATE"));
            if (agent == null || !NORMAL.equals(agent.getDelFlag())) throw new ServiceException("Agent不存在");
            if (!LegacyRuntimeAgentRequests.LEGACY_PLAN.equals(agent.getAgentFamily()))
                throw new ServiceException("AGENT_FAMILY_MISMATCH", 409);
        }
        AidBusinessGroupMember member = memberMapper.selectOne(Wrappers.<AidBusinessGroupMember>lambdaQuery()
                .eq(AidBusinessGroupMember::getGroupId, request.getGroupId())
                .eq(AidBusinessGroupMember::getMemberType, request.getMemberType())
                .eq(AidBusinessGroupMember::getMemberId, request.getMemberId()).last("FOR UPDATE"));
        if (member == null) {
            member = new AidBusinessGroupMember();
            member.setGroupId(request.getGroupId());
            member.setMemberType(request.getMemberType());
            member.setMemberId(request.getMemberId());
            member.setCreateBy(operator);
            member.setCreateTime(new Date());
        }
        member.setSortOrder(request.getSortOrder());
        member.setDelFlag(NORMAL);
        member.setUpdateBy(operator);
        member.setUpdateTime(new Date());
        try {
            if (member.getId() == null) memberMapper.insert(member);
            else memberMapper.updateById(member);
        } catch (DuplicateKeyException error) { throw new ServiceException("成员已加入分组"); }
        return member;
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(BusinessGroupRequests.MemberRemove request, String operator) {
        AidBusinessGroupMember member = memberMapper.selectOne(Wrappers.<AidBusinessGroupMember>lambdaQuery()
                .eq(AidBusinessGroupMember::getGroupId, request.getGroupId())
                .eq(AidBusinessGroupMember::getMemberType, request.getMemberType())
                .eq(AidBusinessGroupMember::getMemberId, request.getMemberId())
                .eq(AidBusinessGroupMember::getDelFlag, NORMAL).last("FOR UPDATE"));
        if (member == null) return;
        member.setDelFlag("1");
        member.setUpdateBy(operator);
        member.setUpdateTime(new Date());
        memberMapper.updateById(member);
    }

    public List<AidBusinessGroupMember> activeMemberRows(Long groupId) {
        return memberMapper.selectList(Wrappers.<AidBusinessGroupMember>lambdaQuery()
                .eq(AidBusinessGroupMember::getGroupId, groupId)
                .eq(AidBusinessGroupMember::getDelFlag, NORMAL)
                .orderByAsc(AidBusinessGroupMember::getSortOrder).orderByAsc(AidBusinessGroupMember::getId));
    }

    /** A group is a single callable contract; each active Skill member must use the same I/O schema. */
    public void assertSkillSchemaCompatible(Long skillId, String inputSchema, String outputSchema) {
        for (AidBusinessGroupMember membership : memberMapper.selectList(
                Wrappers.<AidBusinessGroupMember>lambdaQuery()
                        .eq(AidBusinessGroupMember::getMemberType, "SKILL")
                        .eq(AidBusinessGroupMember::getMemberId, skillId)
                        .eq(AidBusinessGroupMember::getDelFlag, NORMAL))) {
            assertGroupSchema(membership.getGroupId(), skillId, inputSchema, outputSchema);
        }
    }

    private void assertGroupSchema(Long groupId, Long skillId, String inputSchema, String outputSchema) {
        List<AidBusinessGroupMember> peers = memberMapper.selectList(
                Wrappers.<AidBusinessGroupMember>lambdaQuery()
                        .eq(AidBusinessGroupMember::getGroupId, groupId)
                        .eq(AidBusinessGroupMember::getMemberType, "SKILL")
                        .eq(AidBusinessGroupMember::getDelFlag, NORMAL)
                        .ne(AidBusinessGroupMember::getMemberId, skillId));
        if (peers.isEmpty()) return;
        List<Long> peerIds = peers.stream().map(AidBusinessGroupMember::getMemberId).distinct().toList();
        for (AidSkill peer : skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                .in(AidSkill::getId, peerIds).eq(AidSkill::getDelFlag, NORMAL))) {
            if (peer.getCurrentVersionId() == null) continue;
            AidSkillVersion version = skillVersionMapper.selectById(peer.getCurrentVersionId());
            if (version == null || !NORMAL.equals(version.getDelFlag())) continue;
            if (!Objects.equals(schema(inputSchema), schema(version.getInputSchemaJson()))
                    || !Objects.equals(schema(outputSchema), schema(version.getOutputSchemaJson()))) {
                throw new ServiceException("分组内 Skill 输入输出结构必须一致：" + peer.getSkillCode());
            }
        }
    }

    private Object schema(String source) {
        var contract = JSON.parseObject(StrUtil.blankToDefault(source, "{}"));
        // Published examples are versioned teaching data, not group argument constraints.
        contract.remove("examples");
        // STRUCTURED only labels the existing schema; NONE/FREE_FORM change input semantics.
        if ("STRUCTURED".equals(contract.getString("x-aid-input-mode"))) {
            contract.remove("x-aid-input-mode");
        }
        return contract;
    }
}
