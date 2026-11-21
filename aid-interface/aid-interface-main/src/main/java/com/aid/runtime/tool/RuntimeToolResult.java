package com.aid.runtime.tool;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Typed result returned to the durable Runtime step. */
@Data
public class RuntimeToolResult
{
    private String status;
    private String outputJson;
    private List<String> outputArtifactRefs = new ArrayList<>();
    private List<RuntimeContextRevisionUpdate> contextRevisionUpdates = new ArrayList<>();
    private Long mediaTaskId;
    private String safeSummary;
    private String errorCode;
    private boolean retryable;
    /** Adapter-owned durable negative entry proof; not set from model/tool arguments. */
    private boolean submissionNotEntered;

    public static RuntimeToolResult succeeded(String outputJson)
    {
        RuntimeToolResult result = new RuntimeToolResult();
        result.setStatus("SUCCEEDED");
        result.setOutputJson(outputJson);
        return result;
    }

    /** A known execution result failed its output contract. Retain it; do not resubmit the operation. */
    public RuntimeToolResult failOutputContract(String code,String summary) {
        status="FAILED";errorCode=code;safeSummary=summary;retryable=false;submissionNotEntered=false;return this;
    }

    public static RuntimeToolResult unknown(String errorCode, String safeSummary)
    {
        RuntimeToolResult result = new RuntimeToolResult();
        result.setStatus("UNKNOWN");
        result.setErrorCode(errorCode);
        result.setSafeSummary(safeSummary);
        return result;
    }

    /** Host admission has not entered an external executor. Reuse this Runtime step when due. */
    public static RuntimeToolResult waitingResource(String safeSummary)
    {
        RuntimeToolResult result=new RuntimeToolResult();result.setStatus("WAITING_RESOURCE");
        result.setSafeSummary(safeSummary);result.setErrorCode("TOOL_WAITING_RESOURCE");return result;
    }

    public static RuntimeToolResult waitingResource(Long position) {
        RuntimeToolResult result=waitingResource(RuntimeResourceWait.message(position));
        result.setOutputJson(RuntimeResourceWait.metadata(position));return result;
    }
}
