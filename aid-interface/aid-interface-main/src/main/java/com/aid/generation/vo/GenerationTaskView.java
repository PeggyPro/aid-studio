package com.aid.generation.vo;

import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.TaskEtaVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** 通用生成接口使用的任务视图，确保平台雪花 ID 对 JavaScript 无损。 */
@Data
@Builder
public class GenerationTaskView {
    private Object taskId;
    /** Only populated when this generic generation task is bound to a canvas node. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String workspaceId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String objectId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long inputObjectVersion;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String operationId;
    private String mediaType;
    private String protocol;
    private String modelName;
    private String status;
    private Integer queuePosition;
    private TaskEtaVO eta;
    private String providerTaskId;
    @MediaUrl
    private String originUrl;
    @MediaUrl
    private String ossUrl;
    private String textContent;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson2.annotation.JSONField(serialize = false, deserialize = false)
    private MediaTextGenerateRequest.TextMessageItem toolMessage;
    private Boolean toolContextAvailable;
    private String errorMessage;
    private String errorCode;
    private String errorType;
    private String errorSource;
    private String userMessage;
    private boolean needRecharge;
    private String rechargeOwner;
    private boolean retryable;
    private String refundStatus;
    private String billingStatus;
    private BigDecimal preHoldAmount;
    private BigDecimal actualCost;
    private String billingMode;
    private Long outputDurationSeconds;

    public static GenerationTaskView from(MediaTaskResponse source) {
        if (source == null) return null;
        return GenerationTaskView.builder()
                .taskId(JsonSafeId.value(source.getTaskId()))
                .workspaceId(source.getWorkspaceId())
                .objectId(source.getObjectId())
                .inputObjectVersion(source.getInputObjectVersion())
                .operationId(source.getOperationId())
                .mediaType(source.getMediaType())
                .protocol(source.getProtocol())
                .modelName(source.getModelName())
                .status(publicStatus(source.getStatus()))
                .queuePosition(source.getQueuePosition())
                .eta(source.getEta())
                .providerTaskId(source.getProviderTaskId())
                .originUrl(source.getOriginUrl())
                .ossUrl(source.getOssUrl())
                .textContent(source.getTextContent())
                .toolMessage(source.getToolMessage())
                .toolContextAvailable(source.getToolContextAvailable())
                .errorMessage(source.getErrorMessage())
                .errorCode(source.getErrorCode())
                .errorType(source.getErrorType())
                .errorSource(source.getErrorSource())
                .userMessage(source.getUserMessage())
                .needRecharge(source.isNeedRecharge())
                .rechargeOwner(source.getRechargeOwner())
                .retryable(source.isRetryable())
                .refundStatus(source.getRefundStatus())
                .billingStatus(source.getBillingStatus())
                .preHoldAmount(source.getPreHoldAmount())
                .actualCost(source.getActualCost())
                .billingMode(source.getBillingMode())
                .outputDurationSeconds(source.getOutputDurationSeconds())
                .build();
    }

    private static String publicStatus(String status) {
        return "WAIT_POLL".equals(status) || "WAIT_CALLBACK".equals(status)
                ? "PROCESSING" : status;
    }
}
