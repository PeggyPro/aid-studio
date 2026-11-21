package com.aid.canvas.service;

import java.util.Map;
import com.aid.canvas.dto.CanvasRequests;

/** Identifies subjects in an uploaded frame from an authorized canvas workspace. */
public interface CanvasVideoSubjectDetectionService {
    Map<String, Object> detect(CanvasRequests.VideoSubjectDetect request, Long userId);
}
