package com.aid.media.provider;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe transport metadata; contains no endpoint, authorization, payload or exception message. */
public final class TextTransportFailure extends IOException {
    private final Map<String, Object> diagnostics;

    private TextTransportFailure(IOException cause, String phase, HttpResponse<?> response) {
        super("文本上游传输异常", cause);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("phase", phase);
        if (response != null) {
            values.put("protocol", response.version().name());
            values.put("httpStatus", response.statusCode());
            for (String header : new String[] {"x-request-id", "request-id", "cf-ray"}) {
                response.headers().firstValue(header).filter(v -> v.matches("[a-zA-Z0-9_.:/-]{1,128}"))
                        .ifPresent(v -> values.put(header, v));
            }
        }
        String message = cause.getMessage();
        if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("rst_stream")) {
            values.put("signal", "HTTP2_RESET");
            String normalized = message.toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
            for (String code : new String[] {"REFUSED_STREAM", "INTERNAL_ERROR", "PROTOCOL_ERROR",
                    "FLOW_CONTROL_ERROR", "ENHANCE_YOUR_CALM", "CANCEL", "NO_ERROR"}) {
                if (normalized.contains(code)) { values.put("resetCode", code); break; }
            }
        }
        diagnostics = Map.copyOf(values);
    }

    public static IOException capture(IOException cause, String phase, HttpResponse<?> response) {
        return cause instanceof TextTransportFailure ? cause : new TextTransportFailure(cause, phase, response);
    }
    public Map<String, Object> diagnostics() { return diagnostics; }
}
