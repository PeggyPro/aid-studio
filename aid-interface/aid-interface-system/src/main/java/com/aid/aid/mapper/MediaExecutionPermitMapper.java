package com.aid.aid.mapper;

import com.aid.aid.domain.media.MediaExecutionPermit;
import com.aid.aid.domain.media.MediaExecutionPermitCount;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** All admission mutations are serialized by the same durable guard row. */
public interface MediaExecutionPermitMapper {
    Integer lockAdmission();
    Integer lockMigration();
    int importLegacyActiveTasks();
    int bindLegacyTaskTokens();
    int markReady();
    int expireLocalLeases();
    List<String> selectTerminalTokens();
    long countActive(@Param("userBucket") String userBucket, @Param("modelCode") String modelCode,
                     @Param("providerId") Long providerId);
    com.aid.aid.domain.media.MediaAdmissionSnapshot admissionSnapshot(
            @Param("taskIds") List<Long> taskIds, @Param("userBucket") String userBucket,
            @Param("modelCode") String modelCode, @Param("providerId") Long providerId);
    int insertReserved(@Param("permit") MediaExecutionPermit permit, @Param("leaseSeconds") int leaseSeconds);
    MediaExecutionPermit selectByToken(@Param("token") String token);
    String selectActiveTokenForTask(@Param("taskId") Long taskId);
    Integer ownedActive(@Param("token") String token, @Param("ownerId") String ownerId);
    int activate(@Param("token") String token, @Param("ownerId") String ownerId,
                 @Param("executionMode") String executionMode, @Param("leaseSeconds") int leaseSeconds);
    int renew(@Param("ownerId") String ownerId, @Param("tokens") List<String> tokens,
              @Param("leaseSeconds") int leaseSeconds);
    int release(@Param("token") String token, @Param("reason") String reason);
    int promoteAcceptedTask(@Param("token") String token);
    int promoteAcceptedTasks();
    int releaseReturnedSynchronous(@Param("token") String token, @Param("taskId") Long taskId,
                                   @Param("executionMode") String executionMode);
    List<MediaExecutionPermitCount> countModels(@Param("codes") List<String> codes);
    List<MediaExecutionPermitCount> countProviders(@Param("ids") List<Long> ids);
}
