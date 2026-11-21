package com.aid.generation.service;

import com.aid.generation.dto.GenerationRequests;
import com.aid.media.dto.MediaTaskResponse;

import java.util.function.Supplier;

/** 将通用生成任务绑定到可选业务目标，生成门面无需感知目标存储实现。 */
public interface GenerationTargetBindingService {
    MediaTaskResponse submitNode(GenerationRequests.Base request, Long userId, String mediaType,
                                 String requestHash, String callId,
                                 Supplier<MediaTaskResponse> submitter);

    /** Resolve an existing canvas submission through its authoritative task binding. */
    MediaTaskResponse visibleNodeTask(Long workspaceId, Long taskId, Long userId);

    MediaTaskResponse streamNode(GenerationRequests.Base request, Long userId, String mediaType,
                                 String requestHash, String callId,
                                 StreamStarter streamStarter);

    @FunctionalInterface
    interface StreamStarter {
        void start(java.util.function.Consumer<MediaTaskResponse> onPrepared);
    }
}
