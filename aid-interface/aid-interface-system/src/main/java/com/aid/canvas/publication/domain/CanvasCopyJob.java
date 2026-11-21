package com.aid.canvas.publication.domain;
import lombok.Data;
import java.util.Date;
@Data public class CanvasCopyJob {
    private Long id,userId,publicationId,targetProjectId,totalBytes,completedBytes;
    private String operationId,requestHash,projectName,status,workerToken,errorCode;
    private Integer totalResources,completedResources,attempts;
    private Boolean cancelRequested;
    private Date leaseUntil,createTime,updateTime;
}
