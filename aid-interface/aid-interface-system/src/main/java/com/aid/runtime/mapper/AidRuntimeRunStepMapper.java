package com.aid.runtime.mapper;

import com.aid.runtime.domain.AidRuntimeRunStep;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Runtime step attempt persistence. */
@Mapper
public interface AidRuntimeRunStepMapper extends BaseMapper<AidRuntimeRunStep>
{
    /** Bounded projection for cross-plan comparison; no Skill body, model route or full approval read. */
    @org.apache.ibatis.annotations.Select("SELECT s.id AS stepId, "
            + "JSON_UNQUOTE(JSON_EXTRACT(a.scope_json,'$.tool.argumentsJson')) AS argumentsJson, "
            + "JSON_UNQUOTE(JSON_EXTRACT(a.scope_json,'$.tool.argumentsDigest')) AS argumentsDigest, "
            + "JSON_UNQUOTE(JSON_EXTRACT(a.scope_json,'$.tool.preparedPayloadJson')) AS preparedPayloadJson, "
            + "JSON_UNQUOTE(JSON_EXTRACT(a.scope_json,'$.tool.preparedPayloadDigest')) AS preparedPayloadDigest "
            + "FROM aid_runtime_run_step s JOIN aid_runtime_approval a ON a.id=s.approval_id "
            + "WHERE s.run_id=#{runId} AND a.run_id=s.run_id AND a.user_id=#{userId} "
            + "AND s.plan_revision < #{planRevision} AND a.plan_revision=s.plan_revision "
            + "AND BINARY s.tool_code=BINARY #{toolCode} AND s.step_kind='TOOL' "
            + "AND s.operation_kind='ASSISTANT_TOOL' AND s.status='SUCCEEDED' AND s.media_task_id IS NOT NULL "
            + "AND s.del_flag='0' AND a.del_flag='0' AND a.scope_kind='PLAN_EXECUTION' "
            + "AND a.status IN ('ACCEPTED','CONSUMED') "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(a.scope_json,'$.tool.billable'))='true' "
            + "AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.scope_json,'$.plan.bindingVersion'))=BINARY #{contractVersion} "
            + "AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.scope_json,'$.tool.schemaDigest'))=BINARY #{schemaDigest} "
            + "ORDER BY s.id DESC LIMIT 101")
    java.util.List<java.util.Map<String, Object>> selectCompletedToolComparisons(
            @org.apache.ibatis.annotations.Param("runId") Long runId,
            @org.apache.ibatis.annotations.Param("userId") Long userId,
            @org.apache.ibatis.annotations.Param("planRevision") Long planRevision,
            @org.apache.ibatis.annotations.Param("toolCode") String toolCode,
            @org.apache.ibatis.annotations.Param("contractVersion") String contractVersion,
            @org.apache.ibatis.annotations.Param("schemaDigest") String schemaDigest);
}
