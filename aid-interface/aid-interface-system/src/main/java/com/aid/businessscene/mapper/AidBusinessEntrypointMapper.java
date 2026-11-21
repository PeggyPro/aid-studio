package com.aid.businessscene.mapper;

import com.aid.businessscene.domain.AidBusinessEntrypoint;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

public interface AidBusinessEntrypointMapper extends BaseMapper<AidBusinessEntrypoint> {
    @Select("SELECT func_code FROM aid_ai_model_func_config WHERE func_code=#{code} AND del_flag='0' FOR UPDATE")
    String lockScene(@Param("code") String code);
    /** An explicit policy reset must persist the empty value; ordinary partial config edits ignore it. */
    @Update("UPDATE sys_config SET config_value=#{value},update_by=#{actor},update_time=CURRENT_TIMESTAMP WHERE config_id=#{id} AND config_key=#{key}")
    int writeParameterValue(@Param("id")Long id,@Param("key")String key,@Param("value")String value,@Param("actor")String actor);
}
