package com.aid.context.mapper;

import com.aid.context.domain.AidConversationContextMemory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Every operation is owner/scope bound. Source access must also be verified by the caller. */
public interface AidConversationContextMemoryMapper {
    @Select("""
            SELECT id,user_id,scope_type,scope_key,revision,source_digest,memory_json,
                   create_time,update_time,del_flag
            FROM aid_conversation_context_memory
            WHERE user_id=#{userId} AND scope_type=#{scopeType} AND scope_key=#{scopeKey}
              AND del_flag='0'
            LIMIT 1
            """)
    AidConversationContextMemory findActive(@Param("userId") Long userId,
                                           @Param("scopeType") String scopeType,
                                           @Param("scopeKey") String scopeKey);

    @Insert("""
            INSERT INTO aid_conversation_context_memory
              (user_id,scope_type,scope_key,revision,source_digest,memory_json,create_time,update_time,del_flag)
            VALUES (#{userId},#{scopeType},#{scopeKey},1,#{sourceDigest},#{memoryJson},NOW(),NOW(),'0')
            ON DUPLICATE KEY UPDATE id=id
            """)
    int insertIfAbsent(@Param("userId") Long userId, @Param("scopeType") String scopeType,
                       @Param("scopeKey") String scopeKey, @Param("sourceDigest") String sourceDigest,
                       @Param("memoryJson") String memoryJson);

    @Select("""
            <script>
            SELECT id,user_id,scope_type,scope_key,revision,source_digest,memory_json,
                   create_time,update_time,del_flag
            FROM aid_conversation_context_memory
            WHERE user_id=#{userId} AND scope_type=#{scopeType} AND del_flag='0'
              AND scope_key IN
              <foreach collection="scopeKeys" item="key" open="(" separator="," close=")">#{key}</foreach>
            </script>
            """)
    java.util.List<AidConversationContextMemory> findActiveBatch(@Param("userId") Long userId,
            @Param("scopeType") String scopeType, @Param("scopeKeys") java.util.List<String> scopeKeys);

    @Insert("""
            <script>
            INSERT INTO aid_conversation_context_memory
              (user_id,scope_type,scope_key,revision,source_digest,memory_json,create_time,update_time,del_flag)
            VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{userId},#{scopeType},#{row.scopeKey},1,#{row.sourceDigest},#{row.memoryJson},NOW(),NOW(),'0')
            </foreach>
            ON DUPLICATE KEY UPDATE id=id
            </script>
            """)
    int insertImmutableBatchIfAbsent(@Param("userId") Long userId, @Param("scopeType") String scopeType,
            @Param("rows") java.util.List<AidConversationContextMemory> rows);

    @Update("""
            UPDATE aid_conversation_context_memory
            SET revision=revision+1,source_digest=#{sourceDigest},memory_json=#{memoryJson},update_time=NOW()
            WHERE user_id=#{userId} AND scope_type=#{scopeType} AND scope_key=#{scopeKey}
              AND revision=#{expectedRevision} AND del_flag='0'
            """)
    int compareAndSet(@Param("userId") Long userId, @Param("scopeType") String scopeType,
                      @Param("scopeKey") String scopeKey, @Param("expectedRevision") long expectedRevision,
                      @Param("sourceDigest") String sourceDigest, @Param("memoryJson") String memoryJson);
}
