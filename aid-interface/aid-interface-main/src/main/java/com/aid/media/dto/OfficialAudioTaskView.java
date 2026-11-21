package com.aid.media.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.media.provider.AudioOutputItem;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/** 官方音频任务返回视图，任务编号按字符串传输，保留所有产物与账务状态。 */
@Data
public class OfficialAudioTaskView {
    private String taskId;
    private String mediaType;
    private String protocol;
    private String modelName;
    private String status;
    private Integer queuePosition;
    private TaskEtaVO eta;
    private String providerTaskId;
    @MediaUrl private String originUrl;
    @MediaUrl private String ossUrl;
    private String textContent;
    private List<AudioOutputItem> audioOutputs;
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

    public static OfficialAudioTaskView from(MediaTaskResponse source) {
        if (source == null) return null;
        OfficialAudioTaskView view = cn.hutool.core.bean.BeanUtil.copyProperties(source, OfficialAudioTaskView.class);
        view.setTaskId(source.getTaskId() == null ? null : source.getTaskId().toString());
        return view;
    }
}
