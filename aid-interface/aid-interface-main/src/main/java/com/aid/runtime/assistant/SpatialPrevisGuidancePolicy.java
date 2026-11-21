package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.dto.RuntimeAgentRequests;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fixed, non-engine execution boundary for the spatial planning Assistant. */
public final class SpatialPrevisGuidancePolicy
{
    public static final String MODULE_CODE = "SPATIAL_PREVIS";
    public static final String AGENT_CODE = "spatial_previs_agent";
    public static final String SKILL_CODE = "spatial_previs";
    public static final String OPERATION_MODE = "GUIDANCE_ONLY";
    public static final String ENGINE_UNAVAILABLE_CODE = "SPATIAL_ENGINE_UNAVAILABLE";
    public static final String ENGINE_UNAVAILABLE_MESSAGE = "空间预演引擎尚未接通";
    public static final Set<String> READ_TOOL_CODES = Set.of(
            "aid.agent.read-resource",
            "aid.skill.resolve-version",
            "aid.skill.read-resource");

    private SpatialPrevisGuidancePolicy()
    {
    }

    public static boolean appliesToModule(String moduleCode)
    {
        return MODULE_CODE.equals(moduleCode);
    }

    public static boolean appliesToAgent(String agentCode)
    {
        return AGENT_CODE.equals(agentCode);
    }

    public static boolean validDraftBindings(RuntimeAgentRequests.DraftPayload payload)
    {
        if (payload == null || payload.getSkillBindings() == null || payload.getToolBindings() == null
                || payload.getSkillBindings().size() != 1
                || payload.getToolBindings().size() != READ_TOOL_CODES.size()) return false;
        RuntimeAgentRequests.SkillBinding skill = payload.getSkillBindings().get(0);
        if (skill == null || !SKILL_CODE.equals(skill.getSkillCode())
                || skill.getSkillId() == null || skill.getSkillVersionId() == null
                || StrUtil.isBlank(skill.getPackageDigest())) return false;
        Set<String> toolCodes = new HashSet<>();
        for (RuntimeAgentRequests.ToolBinding tool : payload.getToolBindings())
        {
            if (tool == null || StrUtil.isBlank(tool.getToolCode())
                    || StrUtil.isBlank(tool.getContractVersion()) || StrUtil.isBlank(tool.getSchemaDigest())
                    || !toolCodes.add(tool.getToolCode())) return false;
        }
        return READ_TOOL_CODES.equals(toolCodes);
    }

    public static void requirePublishedBindings(List<AidRuntimeAgentSkillRef> skills,
                                                List<AidRuntimeAgentToolRef> tools)
    {
        if (skills == null || skills.size() != 1 || tools == null || tools.size() != READ_TOOL_CODES.size())
            reject();
        AidRuntimeAgentSkillRef skill = skills.get(0);
        if (skill == null || !SKILL_CODE.equals(skill.getSkillCode())
                || skill.getSkillId() == null || skill.getSkillVersionId() == null
                || StrUtil.isBlank(skill.getPackageDigest())) reject();
        Set<String> toolCodes = new HashSet<>();
        for (AidRuntimeAgentToolRef tool : tools)
        {
            if (tool == null || !READ_TOOL_CODES.contains(tool.getToolCode())
                    || StrUtil.isBlank(tool.getContractVersion()) || StrUtil.isBlank(tool.getSchemaDigest())
                    || !toolCodes.add(tool.getToolCode())) reject();
        }
        if (!Objects.equals(READ_TOOL_CODES, toolCodes)) reject();
    }

    public static boolean allowsReadTool(String toolCode, String sideEffectLevel, boolean billable)
    {
        return READ_TOOL_CODES.contains(toolCode) && "READ".equals(sideEffectLevel) && !billable;
    }

    private static void reject()
    {
        throw new ServiceException("空间预演方案助手只能固定 spatial_previs Skill 与只读包工具");
    }
}
