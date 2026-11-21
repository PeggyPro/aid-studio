package com.aid.runtime.assistant.execution;

import com.aid.assistant.vo.AssistantExecutionViews;

/** User-safe, complete quote-only command result; never grants execution ownership. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record AssistantPlanExecutionQuoted(String runId, long runRevision,
                                           String approvalId, long approvalRevision,
                                           String previousApprovalId, String stepId,
                                           long planRevision, String scopeKind, String status,
                                           String nextAction, String quoteDisposition,
                                           AssistantOperationQuoteView quote,
                                           long lastEventSeq, boolean executionSubmitted,
                                           boolean referenceRevalidated,
                                           java.util.List<com.alibaba.fastjson2.JSONObject> referenceResources) { }
