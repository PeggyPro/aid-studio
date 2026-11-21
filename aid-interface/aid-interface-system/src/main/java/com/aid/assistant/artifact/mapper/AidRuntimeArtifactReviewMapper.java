package com.aid.assistant.artifact.mapper;

import com.aid.assistant.artifact.domain.AidRuntimeArtifactReview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AidRuntimeArtifactReviewMapper extends BaseMapper<AidRuntimeArtifactReview> {
    @Select("""
            SELECT v.* FROM aid_runtime_artifact_review v
            JOIN aid_runtime_artifact a ON a.id=v.artifact_id AND a.del_flag='0'
            JOIN aid_runtime_run r ON r.id=a.producer_run_id AND r.del_flag='0'
            WHERE v.artifact_id=#{artifactId} AND v.del_flag='0' AND r.user_id=#{userId}
              AND (#{cursor} IS NULL OR v.id < #{cursor})
            ORDER BY v.id DESC LIMIT #{limit}
            """)
    List<AidRuntimeArtifactReview> selectAuthorizedPage(@Param("userId") Long userId,
                                                         @Param("artifactId") Long artifactId,
                                                         @Param("cursor") Long cursor,
                                                         @Param("limit") int limit);

    @Select("""
            SELECT * FROM aid_runtime_artifact_review
            WHERE artifact_id=#{artifactId} AND review_artifact_id=#{reviewArtifactId}
              AND reviewer_run_id=#{reviewerRunId} AND reviewer_step_id=#{reviewerStepId}
              AND del_flag='0' LIMIT 1
            """)
    AidRuntimeArtifactReview selectReceipt(@Param("artifactId") Long artifactId,
                                           @Param("reviewArtifactId") Long reviewArtifactId,
                                           @Param("reviewerRunId") Long reviewerRunId,
                                           @Param("reviewerStepId") Long reviewerStepId);
}
