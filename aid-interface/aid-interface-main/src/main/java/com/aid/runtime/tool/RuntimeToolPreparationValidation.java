package com.aid.runtime.tool;

import com.aid.common.exception.ServiceException;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.media.service.MediaPreparationErrorCode;
import com.aid.media.util.ModelCapabilityResolver;

/** Only use around pure request preparation or quoting, before reservations and task admission. */
public final class RuntimeToolPreparationValidation {
    private RuntimeToolPreparationValidation() { }

    public static RuntimeException mediaQuote(ServiceException error) {
        return mediaQuote(error, null);
    }

    /** Adapters may supply only their existing server-generated structured error code. */
    public static RuntimeException mediaQuote(ServiceException error, String structuredCode) {
        MediaPreparationErrorCode preparationCode = MediaPreparationErrorCode.read(structuredCode);
        if (preparationCode == null) {
            preparationCode = MediaPreparationErrorCode.read(error.getDetailMessage());
        }
        if (preparationCode != null) {
            return switch (preparationCode) {
                case MODEL_REQUIRED -> rejected(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_REQUIRED, error);
                case MODEL_UNAVAILABLE -> rejected(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_UNAVAILABLE, error);
                case MODEL_CAPABILITY_INVALID -> rejected(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_CAPABILITY_INVALID, error);
                case MODEL_PARAMETERS_INVALID -> rejected(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_PARAMETERS_INVALID, error);
                case MEDIA_INPUTS_INVALID -> rejected(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID, error);
                case MEDIA_INPUT_METADATA_REQUIRED -> rejected(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_METADATA_REQUIRED, error);
                case QUOTE_UNAVAILABLE -> rejected(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_QUOTE_UNAVAILABLE, error);
                case QUOTE_SNAPSHOT_INVALID -> rejected(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_QUOTE_INVALID, error);
            };
        }
        var details = TaskErrorSnapshot.read(error.getTaskErrorJson());
        String code = details == null ? structuredCode : details.getErrorCode();
        if ("MODEL_ACCOUNT_UNAVAILABLE".equals(code)) {
            return rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_UNAVAILABLE, error);
        }
        if (code != null && java.util.Set.of("USER_INPUT_INVALID", "USER_INPUT_EMPTY", "USER_INPUT_TOO_LONG",
                "USER_FILE_FORMAT_INVALID", "USER_FILE_TOO_LARGE", "USER_IMAGE_RESOLUTION_INVALID",
                "USER_AUDIO_INVALID", "USER_VIDEO_INVALID").contains(code)) {
            return rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MEDIA_INPUTS_INVALID,
                    error);
        }
        if ("MODEL_PARAMETER_INVALID".equals(error.getDetailMessage())
                || "MODEL_PARAMETER_INVALID".equals(code)
                || "MODEL_PARAMETER_INCOMPATIBLE".equals(code)
                || ModelCapabilityResolver.MSG_SIZE_UNSUPPORTED.equals(error.getMessage())
                || ModelCapabilityResolver.MSG_ASPECT_RATIO_UNSUPPORTED.equals(error.getMessage())) {
            return rejected(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_PARAMETERS_INVALID,
                    error);
        }
        return error;
    }

    private static RuntimeToolPreparationRejectedException rejected(
            RuntimeToolPreparationRejectedException.Reason reason, ServiceException cause) {
        return new RuntimeToolPreparationRejectedException(reason, cause);
    }
}
