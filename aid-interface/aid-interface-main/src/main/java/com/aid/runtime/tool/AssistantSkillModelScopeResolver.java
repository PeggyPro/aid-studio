package com.aid.runtime.tool;

import com.aid.common.exception.ServiceException;
import com.aid.runtime.mapper.AssistantSkillDirectoryMapper;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.aid.skill.service.SkillModelConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Reads only the authorized fixed version's model configuration, never Skill instructions/resources. */
@Component
@RequiredArgsConstructor
public class AssistantSkillModelScopeResolver {
    private final AssistantSkillDirectoryMapper directory;
    private final AidSkillVersionMapper versions;

    public record Scope(SkillModelConfiguration configuration, String capabilityCode) { }

    public Scope resolve(Long agentVersionId, Long userId, String moduleCode, String code,
                                           String expectedVersionId, String expectedDigest, String transport) {
        com.aid.assistant.service.AssistantEntryTransport.require(transport);
        if (agentVersionId == null || userId == null || userId <= 0 || code == null || code.isBlank()
                || code.length() > 64) throw new ServiceException("Skill模型查询目标无效");
        var items = directory.selectPage(agentVersionId, userId, null, code, "OFFICIAL",
                "CANVAS".equals(transport) && "UNIVERSAL".equals(moduleCode), 0, 2, null);
        if (items.size() != 1) throw new ServiceException("当前助手无权查询该Skill模型配置");
        var item = items.get(0);
        if (expectedVersionId != null && (!Objects.equals(expectedVersionId, item.getVersionId())
                || !Objects.equals(expectedDigest, item.getPackageDigest())))
            throw new ServiceException("所选Skill版本已变化，请先确认本轮固定版本");
        var version = versions.selectOne(new QueryWrapper<AidSkillVersion>()
                .select("id", "model_code", "model_config_json",
                        "JSON_OBJECT('capabilityCode', JSON_EXTRACT(definition_json, '$.capabilityCode')) AS definition_json")
                .eq("id", Long.valueOf(item.getVersionId())).eq("package_digest", item.getPackageDigest())
                .eq("status", "0").eq("del_flag", "0"));
        var configuration = SkillModelConfiguration.from(version);
        if (!configuration.isValid()) throw new ServiceException("Skill模型配置不可用");
        var definition = JSON.parseObject(version.getDefinitionJson());
        String capability = definition == null ? null : definition.getString("capabilityCode");
        return new Scope(configuration, capability == null || capability.isBlank() ? null : capability.trim());
    }
}
