package com.aid.canvas.service;

import com.aid.canvas.dto.CanvasRequests;

import java.util.Map;

public interface CanvasImageLayerMergeService {
    Map<String, Object> merge(CanvasRequests.ImageLayerMerge request, Long userId);
}
