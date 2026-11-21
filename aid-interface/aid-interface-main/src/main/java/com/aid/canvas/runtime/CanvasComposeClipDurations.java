package com.aid.canvas.runtime;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSONArray;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** Explicit prefix selections are timeline durations, never replacements for source metadata. */
final class CanvasComposeClipDurations {
    private CanvasComposeClipDurations() { }

    static List<Double> resolve(List<Double> registeredDurations, JSONArray requested,
                                IntFunction<BigDecimal> verifiedSourceDuration) {
        if (requested == null) return List.copyOf(registeredDurations);
        if (requested.isEmpty() || requested.size() != registeredDurations.size()) throw invalid();
        List<BigDecimal> selections = new ArrayList<>();
        // Reject invalid user values before inspecting any media.
        for (Object value : requested) {
            if (!(value instanceof Number)) throw invalid();
            BigDecimal seconds;
            try { seconds = new BigDecimal(value.toString()); }
            catch (NumberFormatException error) { throw invalid(); }
            if (seconds.compareTo(new BigDecimal("0.001")) < 0
                    || seconds.compareTo(new BigDecimal("3600")) > 0) throw invalid();
            selections.add(seconds);
        }
        for (int i = 0; i < selections.size(); i++) {
            BigDecimal actual = verifiedSourceDuration.apply(i);
            if (actual == null || actual.signum() <= 0 || selections.get(i).compareTo(actual) > 0) throw invalid();
        }
        return selections.stream().map(BigDecimal::doubleValue).toList();
    }

    private static ServiceException invalid() {
        return new ServiceException("视频取用时长必须与素材一一对应，并且不超过实际源视频时长")
                .setDetailMessage("CANVAS_COMPOSE_CLIP_DURATION_INVALID");
    }
}
