package com.aid.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded protocol counters only: never retain response text, thinking, arguments or identifiers. */
public final class TextStreamProtocolDiagnostics {
    private long frames, emptyChoices, deltaTextCharacters, reasoningCharacters, toolFragments;
    private long messageTextCharacters, messageToolCalls, nonObjectFrames;
    private String finishReason = "NONE";
    private boolean done;
    private final TextToolArgumentStreamEvidence arguments = new TextToolArgumentStreamEvidence();

    public void accept(JsonNode frame) {
        frames++;
        if (frame == null || !frame.isObject()) { nonObjectFrames++; return; }
        arguments.accept(frame);
        JsonNode choices = frame.path("choices");
        if (!choices.isArray() || choices.isEmpty()) { emptyChoices++; return; }
        // The chat adapter consumes the first choice; observe that same choice without retaining it.
        JsonNode choice = choices.get(0), delta = choice.path("delta"), message = choice.path("message");
        deltaTextCharacters += characters(delta.path("content"));
        reasoningCharacters += characters(delta.path("reasoning_content"));
        toolFragments += arraySize(delta.path("tool_calls"));
        messageTextCharacters += characters(message.path("content"));
        messageToolCalls += arraySize(message.path("tool_calls"));
        JsonNode reason = choice.path("finish_reason");
        if (!reason.isMissingNode() && !reason.isNull()) {
            finishReason = switch (reason.asText()) {
                case "stop", "tool_calls", "length", "content_filter", "function_call" -> reason.asText();
                default -> "OTHER";
            };
        }
    }

    public void endMarker() { done = true; }

    public void verifyToolMessage(TextMessageItem message) {
        arguments.verify(message);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "chat-frame-counters/v1");
        result.put("frames", frames);
        result.put("emptyChoicesFrames", emptyChoices);
        result.put("nonObjectFrames", nonObjectFrames);
        result.put("deltaTextCharacters", deltaTextCharacters);
        result.put("reasoningCharacters", reasoningCharacters);
        result.put("toolFragments", toolFragments);
        result.put("messageTextCharacters", messageTextCharacters);
        result.put("messageToolCalls", messageToolCalls);
        result.put("finishReason", finishReason);
        result.put("doneMarker", done);
        result.put("toolArgumentIntegrity", arguments.snapshot());
        return result;
    }

    private static int characters(JsonNode value) { return value.isTextual() ? value.textValue().length() : 0; }
    private static int arraySize(JsonNode value) { return value.isArray() ? value.size() : 0; }
}
