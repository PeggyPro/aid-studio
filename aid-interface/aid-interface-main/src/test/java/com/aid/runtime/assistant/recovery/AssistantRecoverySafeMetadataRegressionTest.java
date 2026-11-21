package com.aid.runtime.assistant.recovery;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistantRecoverySafeMetadataRegressionTest {
    @Test void structuredServerSnapshotWinsWithoutLoggingItsUserMessage() {
        var error = TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "private input");
        assertEquals("USER_INPUT_INVALID", AssistantRuntimeRecoveryServiceImpl.safeErrorCode(error));
    }
    @Test void stableInternalDetailCanIdentifyPreviouslyUnclassifiedFailures() {
        var error = new ServiceException("private input").setDetailMessage("CANVAS_NODE_VERSION_CHANGED");
        assertEquals("CANVAS_NODE_VERSION_CHANGED", AssistantRuntimeRecoveryServiceImpl.safeErrorCode(error));
    }
    @Test void rawDetailsAndUrlsDoNotAppearInCode() {
        var error = new ServiceException("https://private.invalid/?token=secret").setDetailMessage("private input\nsecret");
        assertEquals("UNCLASSIFIED", AssistantRuntimeRecoveryServiceImpl.safeErrorCode(error));
    }
    @Test void originUsesOnlyCodeOwnedClassMethodAndLine() {
        var error = new RuntimeException("private token=secret");
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("vendor.Secret", "bad", "private", 7),
                new StackTraceElement("com.aid.media.Validation", "prepare", "private/source", 42)});
        assertEquals("com.aid.media.Validation.prepare:42", AssistantRuntimeRecoveryServiceImpl.safeErrorOrigin(error));
    }
    @Test void unknownOrInvalidOriginsNeverExposeArbitraryText() {
        for (StackTraceElement frame : new StackTraceElement[]{new StackTraceElement("vendor.Secret", "bad", "private", 7),
                new StackTraceElement("com.aid.secret\nvalue", "bad", "private", 7),
                new StackTraceElement("com.aid." + "A".repeat(241), "bad", "private", 7)}) {
            var error = new RuntimeException("secret");error.setStackTrace(new StackTraceElement[]{frame});
            assertEquals("UNAVAILABLE", AssistantRuntimeRecoveryServiceImpl.safeErrorOrigin(error));
        }
    }
}
