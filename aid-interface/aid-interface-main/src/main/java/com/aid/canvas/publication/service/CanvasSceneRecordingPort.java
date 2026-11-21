package com.aid.canvas.publication.service;

import java.util.List;

/** Authoritative recording provenance; the canvas caller has already checked project editing permission. */
public interface CanvasSceneRecordingPort {
    int MAX_RECORDED_WAYPOINTS = 18000;
    void requireAppliedTakes(Long projectId, Long workspaceId, List<Reference> references);
    record Reference(String sceneKey, String clipId, String cameraId, Long takeId) {}
}
