package com.aid.assistant.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** Typed requests for the read-only Assistant governance console. */
public final class AssistantAdminRequests
{
    private AssistantAdminRequests()
    {
    }

    @Data
    public static class PageRequest
    {
        private Integer pageNum = 1;
        private Integer pageSize = 20;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class RunQuery extends PageRequest
    {
        private Long runId;
        private Long sessionId;
        private Long userId;
        private String moduleCode;
        private String mode;
        private String status;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createStartTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createEndTime;
    }

    @Data
    public static class RunDetailRequest
    {
        @NotNull
        private Long runId;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ToolQuery extends PageRequest
    {
        private String keyword;
        private String sideEffectLevel;
        private Boolean billable;
        private Boolean enabled;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class BudgetQuery extends PageRequest
    {
        private Long authorizationId;
        private Long runId;
        private Long payerUserId;
        private String status;
    }

    @Data
    public static class BudgetDetailRequest
    {
        @NotNull
        private Long authorizationId;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class InputQuery extends PageRequest
    {
        private Long requestId;
        private Long runId;
        private Long userId;
        private String requestKey;
        private String status;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createStartTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createEndTime;
    }

    @Data
    public static class InputDetailRequest
    {
        @NotNull
        private Long requestId;
    }
}
