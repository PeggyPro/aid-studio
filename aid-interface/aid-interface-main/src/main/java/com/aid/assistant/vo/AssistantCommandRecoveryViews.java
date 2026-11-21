package com.aid.assistant.vo;

import lombok.Data;
import java.util.List;
import java.util.Map;

/** Public command metadata. Original inputs and arbitrary persisted result JSON are never exposed. */
public final class AssistantCommandRecoveryViews
{
    private AssistantCommandRecoveryViews() { }

    @Data
    public static class Scope
    {
        private String sessionId;
        private Long sessionRevision;
        private Long lastEventSeq;
        private String activeRunId;
        private Long activeRunRevision;
    }

    @Data
    public static class Receipt
    {
        private String commandId;
        private String clientRequestId;
        private String commandType;
        private String status;
        private String payloadDigest;
        private String errorCode;
        private Map<String, String> outcome;
    }

    @Data
    public static class Page
    {
        private Scope scope;
        private List<Receipt> items;
        private boolean hasMore;
        private String nextCommandId;
        private String throughCommandId;
    }

    @Data
    public static class Lookup
    {
        private Scope scope;
        /** OBSERVED or NOT_OBSERVED. Absence is never proof of failure or permission to replace the key. */
        private String observation;
        private String recoveryDirective;
        private boolean replacementKeyAllowed;
        private Receipt receipt;
    }
}
