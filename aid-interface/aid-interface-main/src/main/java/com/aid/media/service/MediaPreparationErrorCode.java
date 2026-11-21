package com.aid.media.service;

import com.aid.common.core.domain.ApiErrorDetails;
import com.aid.common.exception.ServiceException;
import java.util.List;

/**
 * Stable server-owned codes for deterministic validation in the read-only media preparation path.
 * Infrastructure, persistence, billing-ledger and Provider-state failures must not use these codes.
 */
public enum MediaPreparationErrorCode
{
    MODEL_REQUIRED,
    MODEL_UNAVAILABLE,
    MODEL_CAPABILITY_INVALID,
    MODEL_PARAMETERS_INVALID,
    MEDIA_INPUTS_INVALID,
    MEDIA_INPUT_METADATA_REQUIRED,
    QUOTE_UNAVAILABLE,
    QUOTE_SNAPSHOT_INVALID;

    public ServiceException reject(String message)
    {
        return new ServiceException(message).setDetailMessage(name());
    }

    public static ServiceException imageMetadataRequired(String field, List<String> resourceIds)
    {
        ApiErrorDetails details = new ApiErrorDetails();
        details.setErrorCode(MEDIA_INPUT_METADATA_REQUIRED.name());
        details.setField(field);
        details.setReason("已授权图片缺少报价所需的可信元数据");
        details.setRetryable(true);
        details.setResourceIds(resourceIds);
        return new ServiceException("图片素材可信元数据缺失或已变化", 422)
                .setClientErrorData(details)
                .setDetailMessage(MEDIA_INPUT_METADATA_REQUIRED.name());
    }

    public static MediaPreparationErrorCode read(String code)
    {
        if (code == null || code.isBlank()) return null;
        try
        {
            return valueOf(code.trim());
        }
        catch (IllegalArgumentException ignored)
        {
            return null;
        }
    }
}
