package com.aid.canvas.service;

import com.aid.canvas.dto.CanvasRequests;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.billing.vo.BillingQuoteVO;

import java.util.Map;

/** 画布节点到统一媒体任务主链的安全适配器。 */
public interface CanvasGenerationService {
    Map<String, Object> prepareAudioAttachment(CanvasRequests.AudioAttachment request, Long userId);
    MediaTaskResponse generateAudio(CanvasRequests.GenerateAudio request, Long userId);
    BillingQuoteVO quoteAudio(CanvasRequests.AudioQuote request, Long userId);
    MediaTaskResponse generateImage(CanvasRequests.GenerateImage request, Long userId);
    MediaTaskResponse generateVideo(CanvasRequests.GenerateVideo request, Long userId);
    BillingQuoteVO quoteVideoTool(CanvasRequests.VideoToolQuote request, Long userId);
    MediaTaskResponse taskDetail(Long workspaceId, Long taskId, Long userId);
    Map<String, Object> activeTasks(Long workspaceId, Long userId);
    Map<String, Object> taskHistory(CanvasRequests.TaskHistory request, Long userId);
    Map<String, Object> selectResult(CanvasRequests.SelectResult request, Long userId);
    Map<String, Object> adoptTextResult(CanvasRequests.AdoptTextResult request, Long userId);
    /** Resolve registered Canvas references with the same validation as interactive generation. */
    void prepareAssistantImageReferences(Long workspaceId, Long userId, String callId,
                                         java.util.List<Long> resourceIds,
                                         com.aid.media.dto.MediaImageGenerateRequest request);
    void prepareAssistantVideoReferences(Long workspaceId, Long userId, String callId,
                                         java.util.List<Long> resourceIds,
                                         com.aid.media.dto.MediaVideoGenerateRequest request);
    /** Explicit graph roles use the same authorized-resource projection as ordinary generation. */
    void prepareAssistantVideoReferences(Long workspaceId, Long userId, String callId,
                                         java.util.List<Long> resourceIds, java.util.Map<Long, String> roles,
                                         com.aid.media.dto.MediaVideoGenerateRequest request);
    /** Trusted Runtime-only adapter; never accepts browser session/token substitutes. */
    MediaTaskResponse submitAssistantMedia(com.aid.canvas.runtime.RuntimeScopeAccess.Scope target,
                                           com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim claim,
                                           String mediaType, String requestHash,
                                           java.util.function.Supplier<MediaTaskResponse> submitter);
    void reconcileBoundTasks();
}
