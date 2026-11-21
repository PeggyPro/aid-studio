package com.aid.billing.mapper;

import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/** 单次调用预算预留Mapper。 */
@Mapper
public interface AidBillingAuthorizationReservationMapper
        extends BaseMapper<AidBillingAuthorizationReservation> {

    @Select("SELECT * FROM aid_billing_authorization_reservation WHERE id = #{id} "
            + "AND del_flag = '0' FOR UPDATE")
    AidBillingAuthorizationReservation selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM aid_billing_authorization_reservation WHERE logical_call_key = #{logicalCallKey} "
            + "AND del_flag = '0' LIMIT 1")
    AidBillingAuthorizationReservation selectByLogicalCallKey(
            @Param("logicalCallKey") String logicalCallKey);

    @Select("SELECT * FROM aid_billing_authorization_reservation WHERE logical_call_key = #{logicalCallKey} "
            + "AND del_flag = '0' LIMIT 1 FOR UPDATE")
    AidBillingAuthorizationReservation selectByLogicalCallKeyForUpdate(
            @Param("logicalCallKey") String logicalCallKey);

    @Select("SELECT * FROM aid_billing_authorization_reservation WHERE media_task_id = #{mediaTaskId} "
            + "AND del_flag = '0' LIMIT 1")
    AidBillingAuthorizationReservation selectByMediaTaskId(@Param("mediaTaskId") Long mediaTaskId);

    /**
     * Selects terminal Assistant/Skill media tasks whose durable budget reservation still needs
     * settlement or refund reconciliation. The caller clamps {@code limit} to 1..200 and must
     * re-read both task and reservation before applying any accounting transition.
     */
    List<Long> selectRecoverableMediaTaskIds(
            @Param("states") Collection<String> states,
            @Param("limit") int limit);
}
