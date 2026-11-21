package com.aid.skill.mapper;

import com.aid.skill.domain.AidSkillRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Skill运行 Mapper。 */
public interface AidSkillRunMapper extends BaseMapper<AidSkillRun> {

    /** Read-only durable lookup for an Assistant-owned fixed-version invocation. */
    @Select("SELECT * FROM aid_skill_run WHERE user_id = #{userId} "
            + "AND invoke_source = 'ASSISTANT_RUNTIME' AND del_flag = '0' "
            + "AND JSON_VALID(input_json) = 1 "
            + "AND CAST(JSON_UNQUOTE(JSON_EXTRACT(input_json, "
            + "'$.assistantBudgetEnvelope.assistantRuntimeRunId')) AS UNSIGNED) = #{assistantRuntimeRunId} "
            + "AND CAST(JSON_UNQUOTE(JSON_EXTRACT(input_json, "
            + "'$.assistantBudgetEnvelope.assistantParentStepId')) AS UNSIGNED) = #{assistantParentStepId} "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(input_json, "
            + "'$.assistantBudgetEnvelope.assistantOuterStableStepKey')) = #{assistantOuterStableStepKey} "
            + "AND CAST(JSON_UNQUOTE(JSON_EXTRACT(input_json, "
            + "'$.assistantBudgetEnvelope.assistantAttemptNo')) AS UNSIGNED) = #{assistantAttemptNo} "
            + "ORDER BY id DESC LIMIT 2")
    List<AidSkillRun> selectByAssistantIdentity(
            @Param("assistantRuntimeRunId") Long assistantRuntimeRunId,
            @Param("assistantParentStepId") Long assistantParentStepId,
            @Param("assistantOuterStableStepKey") String assistantOuterStableStepKey,
            @Param("assistantAttemptNo") Integer assistantAttemptNo,
            @Param("userId") Long userId);
}
