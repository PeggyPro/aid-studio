package com.aid.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Native Responses events: a completed response is required; EOF never invents completion. */
public final class OpenAiResponsesStream {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_BODY_CHARS = 50L * 1024L * 1024L;
    private static final int MAX_EVENT_CHARS = 1_000_000;
    private OpenAiResponsesStream() { }

    public static void post(String url, String key, String authHeader, String authPrefix,
            Map<String, String> headers, String body, TextStreamCallbacks callbacks) throws IOException {
        var response = OpenAiStyleChatStream.openEventResponse(url, key, authHeader, authPrefix, headers, body);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            BoundedResponseBody.Utf8Read errorBody = BoundedResponseBody.requireRead(response.body());
            String error = errorBody.body();
            String safeMessage = errorBody.complete()
                    ? ProviderErrorSanitizer.fromHttp(response.statusCode(), error)
                    : "上游错误响应过大";
            Map<String, Object> usage = Map.of();
            if (errorBody.complete()) {
                try { usage = State.usage(JSON.readTree(error)); } catch (IOException ignored) { }
            }
            callbacks.onFailure(TextFailureBillingPolicy.httpFailureEvidence(
                    response.statusCode(), safeMessage, usage, errorBody.complete()));
            return;
        }
        try (var responseBody = new TextResponseDeadline(
                BoundedResponseBody.requireInputStream(response.body()),
                TimeUnit.MINUTES.toMillis(10))) {
            callbacks.onResponseBody(responseBody);
            State state = new State(callbacks);
            try (var reader = new BoundedResponseBody.Utf8LineReader(
                    responseBody, MAX_BODY_CHARS, MAX_EVENT_CHARS)) {
                StringBuilder frame = new StringBuilder();
                String line;
                while (!state.terminal && (line = reader.readLine()) != null) {
                    if (line.startsWith("\uFEFF")) line = line.substring(1);
                    if (line.startsWith("data:")) {
                        if (!frame.isEmpty()) frame.append('\n');
                        frame.append(line.substring(5).stripLeading());
                        if (frame.length() > MAX_EVENT_CHARS) throw new IOException("文本事件超限");
                    } else if (line.isEmpty() && !frame.isEmpty()) {
                        state.accept(frame.toString());
                        frame.setLength(0);
                    }
                }
                if (!state.terminal && !frame.isEmpty()) state.accept(frame.toString());
            }
            state.finish();
        }
    }

    static final class State {
        private final TextStreamCallbacks callbacks;
        private boolean terminal;
        private boolean completed;
        private boolean failed;
        private boolean sawText;
        private String responseId;
        State(TextStreamCallbacks callbacks) { this.callbacks = callbacks; }

        void accept(String data) throws IOException {
            if (terminal) return;
            JsonNode event = JSON.readTree(data);
            if (event == null || !event.isObject()) throw new IOException("文本事件无效");
            callbacks.onProtocolFrame(event);
            callbacks.onSseDataLine(ReasoningContentSanitizer.sanitizeJson(data));
            String type = event.path("type").asText();
            JsonNode response = event.path("response");
            if (response.hasNonNull("id")) {
                String id = response.path("id").asText();
                if (responseId != null && !responseId.equals(id)) throw new IOException("文本响应身份不一致");
                responseId = id;
            }
            if ("response.output_text.delta".equals(type)) {
                String delta = text(event.path("delta"));
                if (!delta.isEmpty()) { sawText = true; callbacks.onDelta(delta); }
            } else if ("response.reasoning_summary_text.delta".equals(type)) {
                String delta = text(event.path("delta"));
                if (!delta.isEmpty()) callbacks.onPublicReasoningSummaryDelta(delta);
            }
            if (response.isObject()) {
                Map<String, Object> usage = usage(response);
                if (!usage.isEmpty()) callbacks.onUsage(usage);
            }
            boolean unexpectedTool = "response.output_item.added".equals(type)
                    && java.util.Set.of("function_call", "custom_tool_call").contains(event.path("item").path("type").asText());
            for (JsonNode item : response.path("output"))
                unexpectedTool |= java.util.Set.of("function_call", "custom_tool_call").contains(item.path("type").asText());
            if (unexpectedTool || "error".equals(type) || "response.failed".equals(type)
                    || "response.incomplete".equals(type) || event.hasNonNull("error")
                    || response.hasNonNull("error")) {
                failed = true; terminal = true;
                callbacks.onError("response.incomplete".equals(type) ? "文本响应未完成" : "文本生成失败", null);
            } else if ("response.completed".equals(type)) {
                if (!"completed".equals(response.path("status").asText())) throw new IOException("文本终态无效");
                // Some conforming endpoints send the whole message only in the final frame.
                if (!sawText) for (JsonNode item : response.path("output")) {
                    if (!"message".equals(item.path("type").asText())) continue;
                    for (JsonNode part : item.path("content")) if ("output_text".equals(part.path("type").asText())) {
                        String text = text(part.path("text"));
                        if (!text.isEmpty()) { sawText = true; callbacks.onDelta(text); }
                    }
                }
                terminal = true; completed = true;
            }
        }

        void finish() {
            if (failed) return;
            if (!completed || !sawText) {
                failed = true;
                callbacks.onError(completed ? "响应内容为空" : "文本响应未完成", null);
            } else {
                // finish is idempotent; a reconnect/duplicate completed frame cannot bill twice.
                completed = false; failed = true; callbacks.onComplete();
            }
        }

        private static String text(JsonNode value) throws IOException {
            if (!value.isTextual()) throw new IOException("文本增量类型无效");
            return value.asText();
        }

        private static Map<String, Object> usage(JsonNode response) {
            if (response == null || !response.isObject()) return Map.of();
            ObjectNode adapted = JSON.createObjectNode();
            adapted.set("usage", response.path("usage").deepCopy());
            if (adapted.path("usage") instanceof ObjectNode usage) {
                if (usage.path("input_tokens_details") instanceof ObjectNode details) {
                    ObjectNode prompt = details.deepCopy();
                    // DeepSeek's cached/noncached input has no separate cache-write billing bucket.
                    prompt.put("cache_write_tokens", 0);
                    usage.set("prompt_tokens_details", prompt);
                }
                if (usage.path("output_tokens_details").isObject())
                    usage.set("completion_tokens_details", usage.path("output_tokens_details"));
            }
            return OpenAiStyleChatStream.parseUsageFromRoot(adapted);
        }
    }
}
