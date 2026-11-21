package com.aid.previz.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read only the immutable approved payload of a persisted successful build. */
public interface PrevizArtifactMapper {
    @Select("SELECT JSON_UNQUOTE(JSON_EXTRACT(a.scope_json,'$.tool.preparedPayloadJson')) "
            + "FROM aid_runtime_run_step s JOIN aid_runtime_approval a ON a.id=s.approval_id "
            + "WHERE s.id=#{stepId} AND s.run_id=#{runId} AND a.run_id=s.run_id "
            + "AND s.tool_code IN ('aid.previz.build','aid.previz.build-scoped') AND s.step_kind='TOOL' AND s.status='SUCCEEDED' "
            + "AND s.del_flag='0' AND a.del_flag='0' AND a.scope_kind='PLAN_EXECUTION' "
            + "AND a.status IN ('ACCEPTED','CONSUMED') LIMIT 1")
    String approvedBuildPayload(@Param("stepId") Long stepId, @Param("runId") Long runId);

    @Select("SELECT s.output_json FROM aid_runtime_run_step s JOIN aid_runtime_run r ON r.id=s.run_id "
            + "WHERE s.id=#{stepId} AND r.user_id=#{actor} AND s.tool_code='aid.previz.scene-edit' "
            + "AND s.status='SUCCEEDED' AND s.del_flag='0' AND r.del_flag='0' LIMIT 1")
    String sceneEditReceipt(@Param("actor") Long actor,@Param("stepId") Long stepId);
}
