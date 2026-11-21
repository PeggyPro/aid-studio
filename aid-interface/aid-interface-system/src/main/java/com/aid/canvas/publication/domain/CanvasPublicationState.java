package com.aid.canvas.publication.domain;
import lombok.Data;

@Data
public class CanvasPublicationState {
    private Long projectId;
    private Long version;
    private Long publishedId;
    private Long pendingId;
    private Boolean visible;
    private Boolean copyEnabled;
    private Boolean blocked;
    private Long sceneVersion;
    private String sceneDocumentJson;
    private Boolean atomicSceneWrite;
    private Long sourcePublicationId;
    private Long rootPublicationId;
}
