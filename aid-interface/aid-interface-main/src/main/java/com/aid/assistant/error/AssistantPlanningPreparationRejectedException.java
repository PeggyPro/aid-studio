package com.aid.assistant.error;

import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.exception.ServiceException;
import com.aid.media.service.MediaPreparationErrorCode;

import java.util.Set;

/** Deterministic planning preparation rejection before any Provider submission exists. */
public final class AssistantPlanningPreparationRejectedException extends RuntimeException
{
    private static final Set<String> INPUT_ERROR_CODES = Set.of(
            "USER_INPUT_INVALID", "USER_INPUT_EMPTY", "USER_INPUT_TOO_LONG",
            "USER_FILE_FORMAT_INVALID", "USER_FILE_TOO_LARGE", "USER_IMAGE_RESOLUTION_INVALID",
            "USER_AUDIO_INVALID", "USER_VIDEO_INVALID");

    private final Reason reason;

    public AssistantPlanningPreparationRejectedException(Reason reason)
    {
        super(java.util.Objects.requireNonNull(reason).errorCode());
        this.reason = reason;
    }

    public AssistantPlanningPreparationRejectedException(Reason reason, Throwable cause)
    {
        super(java.util.Objects.requireNonNull(reason).errorCode(), cause);
        this.reason = reason;
    }

    public Reason getReason()
    {
        return reason;
    }

    /** Reads only stable server-owned preparation codes; infrastructure failures stay unknown. */
    public static AssistantPlanningPreparationRejectedException fromMediaQuote(ServiceException error)
    {
        MediaPreparationErrorCode preparation = MediaPreparationErrorCode.read(error.getDetailMessage());
        if (preparation != null)
        {
            return new AssistantPlanningPreparationRejectedException(switch (preparation)
            {
                case MODEL_REQUIRED -> Reason.ASSISTANT_PLANNING_MODEL_REQUIRED;
                case MODEL_UNAVAILABLE -> Reason.ASSISTANT_PLANNING_MODEL_UNAVAILABLE;
                case MODEL_CAPABILITY_INVALID -> Reason.ASSISTANT_PLANNING_MODEL_CAPABILITY_INVALID;
                case MODEL_PARAMETERS_INVALID -> Reason.ASSISTANT_PLANNING_MODEL_PARAMETERS_INVALID;
                case MEDIA_INPUTS_INVALID -> Reason.ASSISTANT_PLANNING_INPUTS_INVALID;
                case MEDIA_INPUT_METADATA_REQUIRED -> Reason.ASSISTANT_PLANNING_INPUT_METADATA_REQUIRED;
                case QUOTE_UNAVAILABLE -> Reason.ASSISTANT_PLANNING_QUOTE_UNAVAILABLE;
                case QUOTE_SNAPSHOT_INVALID -> Reason.ASSISTANT_PLANNING_QUOTE_INVALID;
            }, error);
        }
        var taskError = TaskErrorSnapshot.read(error.getTaskErrorJson());
        String code = taskError == null ? null : taskError.getErrorCode();
        if ("USER_INPUT_TOO_LONG".equals(code))
            return new AssistantPlanningPreparationRejectedException(
                    Reason.ASSISTANT_CONTEXT_PARTITION_REQUIRED, error);
        if ("MODEL_ACCOUNT_UNAVAILABLE".equals(code))
        {
            return new AssistantPlanningPreparationRejectedException(
                    Reason.ASSISTANT_PLANNING_MODEL_UNAVAILABLE, error);
        }
        if (code != null && INPUT_ERROR_CODES.contains(code))
        {
            return new AssistantPlanningPreparationRejectedException(
                    Reason.ASSISTANT_PLANNING_INPUTS_INVALID, error);
        }
        if ("MODEL_PARAMETER_INVALID".equals(code) || "MODEL_PARAMETER_INCOMPATIBLE".equals(code))
        {
            return new AssistantPlanningPreparationRejectedException(
                    Reason.ASSISTANT_PLANNING_MODEL_PARAMETERS_INVALID, error);
        }
        return null;
    }

    public enum Reason
    {
        ASSISTANT_NATIVE_TOOL_PROTOCOL_UNAVAILABLE("原生工具协议不可用"),
        ASSISTANT_NATIVE_TOOL_DEFINITION_INVALID("原生工具定义无效"),
        ASSISTANT_BOUND_TOOL_UNAVAILABLE("助手所需的工具当前不可用，本次未提交生成任务"),
        ASSISTANT_PLANNING_MODEL_REQUIRED("请选择可用模型"),
        ASSISTANT_PLANNING_MODEL_UNAVAILABLE("当前模型不可用"),
        ASSISTANT_PLANNING_MODEL_CAPABILITY_INVALID("模型能力配置无效"),
        ASSISTANT_PLANNING_MODEL_PARAMETERS_INVALID("当前模型参数无效"),
        ASSISTANT_PLANNING_INPUTS_INVALID("当前调用输入无效"),
        ASSISTANT_CONTEXT_PARTITION_REQUIRED("上下文超过当前模型预算，请分段处理或选择更大上下文的模型"),
        ASSISTANT_PLANNING_INPUT_METADATA_REQUIRED("图片素材缺少可信元数据，请重新登记后再试"),
        ASSISTANT_PLANNING_QUOTE_UNAVAILABLE("当前调用无法获得可信报价"),
        ASSISTANT_PLANNING_QUOTE_INVALID("报价快照无效"),
        ASSISTANT_TEXT_BALANCE_INSUFFICIENT("积分余额不足"),
        ASSISTANT_TEXT_BUDGET_UNAVAILABLE("文本执行预算不可用");

        private final String message;

        Reason(String message)
        {
            this.message = message;
        }

        public String errorCode()
        {
            return name();
        }

        public String message()
        {
            return message;
        }
    }
}
