package com.aid.generation.controller;

import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.generation.dto.GenerationRequests;
import com.aid.generation.service.GenerationInvocationContext;
import com.aid.generation.service.GenerationStreamSink;
import com.aid.generation.service.IGenericGenerationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 提供文本、图片和视频的稳定通用生成入口。 */
@Slf4j
@RestController
@RequestMapping("/api/user/generation")
@RequiredArgsConstructor
@Tag(name = "通用生成")
public class UserGenerationController {
    private static final long SSE_TIMEOUT_MILLIS = 15L * 60L * 1000L;
    private final IGenericGenerationFacade generationFacade;

    @PostMapping(value = "/text", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "通用文本生成", description = "非流式创建统一文本媒体任务并返回任务状态；可选关联画布节点，多模态内容仅接受已鉴权资源编号。")
    public AjaxResult text(@Valid @RequestBody GenerationRequests.Text request) {
        request.setStream(Boolean.FALSE);
        return AjaxResult.success(generationFacade.submitText(request,
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    @PostMapping("/text/quote")
    @Operation(summary = "通用文本生成报价", description = "按正式文本请求只读解析已有会话、画布文本引用和已登记素材；不创建会话、任务或画布租约。")
    public AjaxResult textQuote(@Valid @RequestBody GenerationRequests.Text request) {
        request.setStream(Boolean.FALSE);
        return AjaxResult.success(generationFacade.quoteText(request,
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    @PostMapping(value = "/text", params = "stream=true", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @CryptoIgnore
    @Operation(summary = "通用文本流式生成", description = "可选关联画布节点；SSE 返回 prepared、delta、reasoning_delta、done、detached 或 failed 事件。")
    public ResponseEntity<SseEmitter> textStream(@Valid @RequestBody GenerationRequests.Text request) {
        request.setStream(Boolean.TRUE);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        try {
            generationFacade.generateTextStream(request,
                    GenerationInvocationContext.user(SecurityUtils.getUserId()),
                    new EmitterSink(emitter, closed));
        } catch (RuntimeException error) {
            log.error("通用文本流准备失败", error);
            send(emitter, closed, "failed", Map.of("message", "请求失败"), true);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        headers.setCacheControl("no-cache");
        headers.set("X-Accel-Buffering", "no");
        headers.setConnection("keep-alive");
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    @PostMapping("/image")
    @Operation(summary = "通用图片生成", description = "创建统一图片媒体任务并可选关联画布节点；参考素材按工作区资源编号批量鉴权并保持请求顺序。")
    public AjaxResult image(@Valid @RequestBody GenerationRequests.Image request) {
        return AjaxResult.success(generationFacade.submitImage(request,
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    @PostMapping("/image/quote")
    @Operation(summary = "通用图片生成报价", description = "按与正式提交相同的模型、能力、业务绑定和图片编辑参数进行只读报价，不创建任务或占用画布节点。")
    public AjaxResult imageQuote(@Valid @RequestBody GenerationRequests.Image request) {
        return AjaxResult.success(generationFacade.quoteImage(request,
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    @PostMapping("/video")
    @Operation(summary = "通用视频生成", description = "创建统一视频媒体任务并可选关联画布节点；首帧、尾帧和参考素材最终由模型能力契约校验。")
    public AjaxResult video(@Valid @RequestBody GenerationRequests.Video request) {
        return AjaxResult.success(generationFacade.submitVideo(request,
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    @PostMapping("/video/quote")
    @Operation(summary = "通用视频生成报价", description = "按正式视频请求只读解析模型能力与已登记素材；不创建会话、任务或画布租约。")
    public AjaxResult videoQuote(@Valid @RequestBody GenerationRequests.Video request) {
        return AjaxResult.success(generationFacade.quoteVideo(request,
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    @PostMapping("/sessions/messages")
    @Operation(summary = "通用生成会话历史", description = "按消息序号游标读取本人通用生成会话，内容块批量加载并保持顺序。")
    public AjaxResult history(@Valid @RequestBody GenerationRequests.HistoryQuery request) {
        return AjaxResult.success(generationFacade.history(request.getSessionId(), request.getBeforeSeq(), request.getLimit(),
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    @PostMapping("/sessions/list")
    @Operation(summary = "本人通用生成会话列表")
    public AjaxResult sessions(@Valid @RequestBody GenerationRequests.SessionListQuery request) {
        return AjaxResult.success(generationFacade.sessions(request.getPageNum(), request.getPageSize(),
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    @PostMapping("/task/status")
    @Operation(summary = "通用生成任务状态", description = "仅返回当前登录用户自己的统一媒体任务。")
    public AjaxResult task(@Valid @RequestBody GenerationRequests.TaskStatusQuery request) {
        return AjaxResult.success(generationFacade.task(request.getTaskId(),
                GenerationInvocationContext.user(SecurityUtils.getUserId())));
    }

    private static final class EmitterSink implements GenerationStreamSink {
        private final SseEmitter emitter;
        private final AtomicBoolean closed;

        private EmitterSink(SseEmitter emitter, AtomicBoolean closed) {
            this.emitter = emitter;
            this.closed = closed;
        }

        @Override
        public void onPrepared(long taskId, Long sessionId, Long messageId) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", JsonSafeId.value(taskId));
            data.put("sessionId", JsonSafeId.value(sessionId));
            data.put("messageId", JsonSafeId.value(messageId));
            send(emitter, closed, "prepared", data, false);
        }

        @Override
        public void onDelta(String content) {
            send(emitter, closed, "delta", Map.of("content", content), false);
        }

        @Override
        public void onPublicReasoningSummaryDelta(String content) {
            send(emitter, closed, "reasoning_delta",
                    Map.of("content", content, "reasoningKind", "PUBLIC_SUMMARY"), false);
        }

        @Override
        public void onDone(String fullText) {
            send(emitter, closed, "done", Map.of("content", fullText), true);
        }

        @Override
        public void onDetached(long taskId) {
            send(emitter, closed, "detached", Map.of("taskId", JsonSafeId.value(taskId)), true);
        }

        @Override
        public void onFailed(String message) {
            send(emitter, closed, "failed", Map.of("message", message), true);
        }
    }

    private static void send(SseEmitter emitter, AtomicBoolean closed, String event,
                             Object data, boolean complete) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            if (complete && closed.compareAndSet(false, true)) emitter.complete();
        } catch (IOException error) {
            closed.set(true);
            emitter.completeWithError(error);
        }
    }
}
