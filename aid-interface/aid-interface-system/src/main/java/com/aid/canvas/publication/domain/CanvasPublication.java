package com.aid.canvas.publication.domain;

import lombok.Data;
import java.util.Date;

/** 一次不可变提审内容；审核状态可变，正文不覆盖。 */
@Data
public class CanvasPublication {
    private Long id;
    private Long projectId;
    private Long ownerUserId;
    private Long workspaceRevision;
    private Long sceneVersion;
    private String operationId;
    private String requestHash;
    private String projectName;
    private String projectDesc;
    private String videoUrl;
    private String coverUrl;
    private String snapshotJson;
    private String contentHash;
    private Boolean allowCopy;
    private String reviewStatus;
    private String reviewReason;
    private Long sourcePublicationId;
    private Long rootPublicationId;
    private Date createTime;
}
