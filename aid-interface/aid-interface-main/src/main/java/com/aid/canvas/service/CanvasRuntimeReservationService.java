package com.aid.canvas.service;

/** Persistent canvas-node occupancy for a complete Agent or Skill run. */
public interface CanvasRuntimeReservationService {
    Reservation reserve(Long runId, Long workspaceId, String objectId, Long expectedVersion,
                        String editSessionId, String lockToken, Long userId);

    Reservation assertReserved(Long runId, Long userId);

    void renew(Long runId);

    void release(Long runId);

    record Reservation(Long projectId, Long workspaceId, String objectId, Long nodeVersion, Long userId) { }
}
