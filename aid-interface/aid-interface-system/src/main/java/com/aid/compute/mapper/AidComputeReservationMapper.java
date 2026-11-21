package com.aid.compute.mapper;

import com.aid.compute.domain.AidComputeReservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface AidComputeReservationMapper extends BaseMapper<AidComputeReservation> {
    @Insert("INSERT INTO aid_compute_reservation(request_key,request_digest,task_key,project_key,engine,task_kind,state,create_time,update_time) VALUES(#{requestKey},#{requestDigest},#{taskKey},#{projectKey},#{engine},#{taskKind},'PENDING',NOW(),NOW()) ON DUPLICATE KEY UPDATE request_key=request_key")
    int insertPending(AidComputeReservation row);
    @Select("SELECT * FROM aid_compute_reservation WHERE request_key=#{key} FOR UPDATE")
    AidComputeReservation lock(@Param("key") String key);

    /** Caller holds the node row lock before reading or changing its occupied reservations. */
    @Select("SELECT * FROM aid_compute_reservation WHERE node_key=#{nodeKey} AND state IN ('RESERVED','SUBMITTED','UNKNOWN')")
    List<AidComputeReservation> occupied(@Param("nodeKey") String nodeKey);

    @Insert("INSERT INTO aid_compute_queue(request_key,queue_key) VALUES(#{key},#{queue}) ON DUPLICATE KEY UPDATE request_key=request_key")
    int insertQueue(@Param("key") String key, @Param("queue") String queue);

    @Select("SELECT queue_key FROM aid_compute_queue WHERE request_key=#{key}")
    String queueKey(@Param("key") String key);

    /** Stable FIFO within the same pool, engine, task kind and original-worker affinity. */
    @Select("SELECT COUNT(*) FROM aid_compute_queue q JOIN aid_compute_reservation r ON r.request_key=q.request_key "
            + "WHERE q.queue_key=#{queue} AND r.state='PENDING' AND q.queue_seq <= "
            + "(SELECT queue_seq FROM aid_compute_queue WHERE request_key=#{key})")
    long queuePosition(@Param("key") String key, @Param("queue") String queue);
}
