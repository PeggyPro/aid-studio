package com.aid.billing.mapper;

import com.aid.billing.domain.AidBillingAuthorization;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Run总预算授权Mapper。 */
@Mapper
public interface AidBillingAuthorizationMapper extends BaseMapper<AidBillingAuthorization> {

    @Select("SELECT * FROM aid_billing_authorization WHERE id = #{id} AND del_flag = '0' FOR UPDATE")
    AidBillingAuthorization selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM aid_billing_authorization WHERE runtime_run_id = #{runtimeRunId} "
            + "AND del_flag = '0' LIMIT 1")
    AidBillingAuthorization selectByRuntimeRunId(@Param("runtimeRunId") Long runtimeRunId);

    @Select("SELECT * FROM aid_billing_authorization WHERE runtime_run_id = #{runtimeRunId} "
            + "AND del_flag = '0' LIMIT 1 FOR UPDATE")
    AidBillingAuthorization selectByRuntimeRunIdForUpdate(@Param("runtimeRunId") Long runtimeRunId);
}
