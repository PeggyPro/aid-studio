package com.aid.media.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TaskRecheckResult {
    private Long taskId;
    private String outcome;
    private String taskStatus;
    private String providerStatus;
    private String message;
    private boolean terminal;
    private boolean querySuccessful;
}
