package com.aid.generation.listener;

import com.aid.generation.service.GenerationConversationStore;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.aid.media.service.IMediaGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 将统一媒体任务终态同步到已关联的通用会话消息。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationConversationTaskListener {
    private final IMediaGenerationService mediaGenerationService;
    private final GenerationConversationStore conversationStore;

    @EventListener
    public void onCompleted(MediaTaskCompletedEvent event) {
        synchronize(event.getTaskId());
    }

    @EventListener
    public void onOssPersisted(MediaTaskOssPersistedEvent event) {
        synchronize(event.getTaskId());
    }

    public void reconcilePending() {
        for (Long taskId : conversationStore.pendingTaskIds(100)) synchronize(taskId);
    }

    private void synchronize(Long taskId) {
        try {
            MediaTaskResponse task = mediaGenerationService.queryTaskLocal(taskId);
            conversationStore.complete(task);
        } catch (RuntimeException error) {
            log.error("通用会话任务终态同步失败, taskId={}", taskId, error);
        }
    }
}
