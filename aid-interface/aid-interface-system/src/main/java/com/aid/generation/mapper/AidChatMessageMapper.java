package com.aid.generation.mapper;

import com.aid.generation.domain.AidChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 通用会话消息 Mapper。 */
public interface AidChatMessageMapper extends BaseMapper<AidChatMessage> {

    /**
     * 同时补偿未收口消息和已成功但仍保存上游临时地址的图片/视频消息。
     */
    @Select({
            "SELECT candidates.media_task_id FROM (",
            " SELECT m.media_task_id, m.update_time FROM aid_chat_message m",
            " WHERE m.role = 'ASSISTANT' AND m.media_task_id IS NOT NULL AND m.del_flag = '0'",
            "   AND m.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')",
            " UNION ALL",
            " SELECT m.media_task_id, m.update_time FROM aid_chat_message m",
            " JOIN aid_media_task t ON t.id = m.media_task_id",
            " LEFT JOIN aid_chat_message_part p ON p.message_id = m.id",
            "   AND p.resource_role = 'RESULT' AND p.del_flag = '0'",
            " WHERE m.role = 'ASSISTANT' AND m.media_task_id IS NOT NULL AND m.del_flag = '0'",
            "   AND m.status = 'SUCCEEDED' AND m.media_type IN ('IMAGE', 'VIDEO')",
            "   AND t.status = 'SUCCEEDED' AND t.oss_url IS NOT NULL AND t.oss_url <> ''",
            "   AND (p.id IS NULL OR p.resource_url IS NULL OR BINARY p.resource_url <> BINARY t.oss_url)",
            ") candidates GROUP BY candidates.media_task_id",
            "ORDER BY MIN(candidates.update_time) ASC LIMIT #{limit}"
    })
    List<Long> selectConversationReconcileTaskIds(@Param("limit") int limit);
}
