package com.aid.canvas.publication.domain;
import lombok.Data;
import java.util.Date;
@Data public class CanvasPublicationFileIntent {
    private String id,scopeId,kind,location,storageIdentity,extension,status;
    private Long publicationId,projectId;
    private Integer attempts;
    private Date expiresAt,nextAttemptAt,createTime,updateTime;
}
