package com.aid.runtime.tool;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.exception.ServiceException;
import com.aid.media.util.ModelCapabilityResolver;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeMediaPreparationValidationRegressionTest {
    private String reason(ServiceException error) {
        var classified = RuntimeToolPreparationValidation.mediaQuote(error);
        assertInstanceOf(RuntimeToolPreparationRejectedException.class, classified);
        return classified.getMessage();
    }
    @Test void structuredMissingMediaInputBecomesDeterministicPreparationFailure() {
        assertEquals("TOOL_PREPARATION_MEDIA_INPUTS_INVALID", reason(
                TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "unit input")));
    }
    @Test void structuredUnsupportedModelParameterBecomesDeterministicPreparationFailure() {
        assertEquals("TOOL_PREPARATION_MODEL_PARAMETERS_INVALID", reason(
                TaskErrorPresentation.fromCode(TaskErrorCode.MODEL_PARAMETER_INCOMPATIBLE, "unit parameter")));
    }
    @Test void legacyStructuredBillingParameterCodeStillWorks() {
        assertEquals("TOOL_PREPARATION_MODEL_PARAMETERS_INVALID", reason(
                new ServiceException("private detail").setDetailMessage("MODEL_PARAMETER_INVALID")));
    }
    @Test void legacyExactSizeAndAspectFailuresStillWork() {
        for (String message : new String[]{ModelCapabilityResolver.MSG_SIZE_UNSUPPORTED,
                ModelCapabilityResolver.MSG_ASPECT_RATIO_UNSUPPORTED})
            assertEquals("TOOL_PREPARATION_MODEL_PARAMETERS_INVALID", reason(new ServiceException(message)));
    }
    @Test void accessInfrastructureAndBillingFailuresPreserveTheirOriginalException() {
        for (ServiceException error : new ServiceException[]{new ServiceException("CANVAS_ACCESS_DENIED"),
                new ServiceException("unit infrastructure failure"),
                TaskErrorPresentation.fromCode(TaskErrorCode.USER_BALANCE_NOT_ENOUGH, "balance"),
                TaskErrorPresentation.fromCode(TaskErrorCode.UPSTREAM_TIMEOUT, "timeout")})
            assertSame(error, RuntimeToolPreparationValidation.mediaQuote(error));
    }
    @Test void rawProviderTextCannotImpersonateAValidationSnapshot() {
        var error = new ServiceException("USER_INPUT_INVALID");
        assertSame(error, RuntimeToolPreparationValidation.mediaQuote(error));
    }
    @Test void corruptedOversizedOrUnknownSnapshotCannotChangeClassification() {
        for (String data : new String[]{"broken", "x".repeat(4001), "{\"errorCode\":\"FAKE_ERROR\"}"}) {
            var error = new ServiceException("unclassified").setTaskErrorJson(data);
            assertSame(error, RuntimeToolPreparationValidation.mediaQuote(error));
        }
    }
    @Test void adapterStructuredParameterCodeIsPreservedWithoutParsingRawMessage() {
        var error = new ServiceException("private input");
        var classified = RuntimeToolPreparationValidation.mediaQuote(error,"MODEL_PARAMETER_INVALID");
        assertEquals("TOOL_PREPARATION_MODEL_PARAMETERS_INVALID",classified.getMessage());
        assertSame(error,RuntimeToolPreparationValidation.mediaQuote(error,"UPSTREAM_TIMEOUT"));
    }
}
