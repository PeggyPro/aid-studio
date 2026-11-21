package com.aid.media.reconciliation;

import cn.hutool.json.JSONUtil;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded local diagnostics only; never evidence of provider acceptance or billable outcome. */
public final class MediaSubmissionFailureDiagnostics {
    private MediaSubmissionFailureDiagnostics() { }

    public static String snapshot(Throwable failure) {
        try {
            List<Object> causes = new ArrayList<>();
            var seen = new IdentityHashMap<Throwable, Boolean>();
            for (Throwable current = failure; current != null && causes.size() < 4
                    && seen.put(current, Boolean.TRUE) == null; current = current.getCause()) {
                String message = current.getMessage();
                String normalized = message == null ? ""
                        : message.substring(0, Math.min(2048, message.length())).toLowerCase(Locale.ROOT);
                String signal = normalized.contains("rst_stream") ? "HTTP2_RESET"
                        : normalized.contains("connection reset") ? "CONNECTION_RESET"
                        : normalized.contains("timed out") || normalized.contains("timeout") ? "TIMEOUT"
                        : normalized.contains("closed") ? "CLOSED"
                        : normalized.contains("unexpected end") || normalized.contains("eof") ? "EOF"
                        : "UNCLASSIFIED";
                List<String> origins = new ArrayList<>();
                for (StackTraceElement frame : current.getStackTrace()) {
                    if (origins.size() == 4) break;
                    origins.add(identifier(frame.getClassName()) + "." + identifier(frame.getMethodName())
                            + ":" + frame.getLineNumber());
                }
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("type", identifier(current.getClass().getName()));
                entry.put("signal", signal);
                entry.put("origins", origins);
                if (current instanceof com.aid.media.provider.TextTransportFailure transport)
                    entry.put("transport", transport.diagnostics());
                causes.add(entry);
            }
            // Exception messages, request/response bodies, file paths and credentials are never stored.
            return JSONUtil.toJsonStr(Map.of("schema", "submission-failure-diagnostics/v1",
                    "diagnosticOnly", true, "causes", causes));
        } catch (RuntimeException unavailable) {
            return "{\"schema\":\"submission-failure-diagnostics/v1\",\"diagnosticOnly\":true,\"unavailable\":true}";
        }
    }

    private static String identifier(String value) {
        return value.substring(0, Math.min(160, value.length())).replaceAll("[^a-zA-Z0-9_.$]", "?");
    }
}
