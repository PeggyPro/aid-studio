package com.aid.assistant.realtime;

import lombok.Data;

import java.util.Date;

/** Assistant realtime delivery signal. */
@Data
public class AssistantRealtimeSignal
{
    private String type;
    private Long eventId;
    private Long sessionId;
    private Long seq;
    private Long runId;
    private String activityId;
    private Long outerStepId;
    private Long innerStepId;
    private Long skillRunId;
    private String streamId;
    private String activityKind;
    private String safeSummary;
    private String status;
    private Long sourceEventSeq;
    private String stepKind;
    private String eventType;
    private String stage;
    private Long stepId;
    private Long mediaTaskId;
    private String entityId;
    private Long entityRevision;
    private String causationId;
    private String payloadJson;
    private Date createTime;
}
