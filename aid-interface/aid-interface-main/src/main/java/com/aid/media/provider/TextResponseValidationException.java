package com.aid.media.provider;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A locally rejected provider receipt, never proof that a media tool was executed. */
public final class TextResponseValidationException extends RuntimeException {
    public enum Reason {
        TOOL_COUNT_LIMIT, TOOL_CALL_MISSING, TOOL_ID_INVALID, TOOL_ID_DUPLICATE,
        TOOL_NAME_INVALID, TOOL_ARGUMENTS_MISSING, TOOL_ARGUMENTS_LIMIT,
        TOOL_RECEIPT_INVALID, SSE_EVENT_INVALID, TOOL_STREAM_ASSEMBLY_MISMATCH, SSE_EVENT_AFTER_FINISH
    }

    private final String taskErrorJson;
    private final Reason reason;
    private final int callIndex;
    private final int argumentChars;

    public TextResponseValidationException(Reason reason, int callIndex, int argumentChars) {
        super(reason == Reason.SSE_EVENT_INVALID
                ? "模型返回的数据格式无效，本次分析未完成"
                : "模型返回的工具调用格式无效，本次工具未执行");
        this.reason = reason;
        this.callIndex = callIndex;
        this.argumentChars = argumentChars;
        try {
            ObjectMapper mapper = new ObjectMapper();
            TaskErrorResult error = TaskErrorResult.of(TaskErrorCode.RESULT_FORMAT_INVALID);
            error.setUserMessage(getMessage());
            ObjectNode snapshot = (ObjectNode) mapper.readTree(TaskErrorSnapshot.write(error));
            ObjectNode diagnostic = snapshot.putObject("responseValidation");
            diagnostic.put("reason", reason.name());
            diagnostic.put("callIndex", callIndex);
            diagnostic.put("argumentChars", argumentChars);
            // No raw arguments, names, provider text, prompts or credentials in diagnostics.
            taskErrorJson = mapper.writeValueAsString(snapshot);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("Cannot encode response validation evidence", impossible);
        }
    }

    public String getTaskErrorJson() { return taskErrorJson; }
    public ServiceException asException() {
        ServiceException error = new ServiceException(getMessage());
        error.setTaskErrorJson(taskErrorJson);
        return error;
    }
    public Reason reason() { return reason; }
    public int callIndex() { return callIndex; }
    public int argumentChars() { return argumentChars; }
}
