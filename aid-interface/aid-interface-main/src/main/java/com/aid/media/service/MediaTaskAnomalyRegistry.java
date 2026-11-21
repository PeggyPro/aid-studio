package com.aid.media.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.media.provider.ProviderTaskResult;

public interface MediaTaskAnomalyRegistry {
    void recordQueryFailure(AidMediaTask task, String reason, ProviderTaskResult result);
    void resolveTerminal(Long taskId, String taskStatus, String resolutionType);
    void invalidateConfig();
}
