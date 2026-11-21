package com.aid.assistant.service;

/** Routes accepted user corrections through the existing settlement and replan boundary. */
public final class AssistantInterjectionBoundaryPolicy
{
    private AssistantInterjectionBoundaryPolicy() { }

    public static boolean needsSettlementInspection(String status)
    {
        return "PAUSED".equals(status) || "WAITING_APPROVAL".equals(status);
    }

    public static Route route(String status, boolean hasOutstandingExecution)
    {
        // PAUSING keeps its existing stop/pause owner; a queued message cannot steal that transition.
        // Active runs always cross the fenced recovery workflow, including between two submissions.
        // This avoids relying on a momentarily empty in-flight query while a worker still owns a lease.
        if ("RUNNING".equals(status) || "RECONCILING".equals(status)) return Route.DEFERRED_REPLAN;
        if (!needsSettlementInspection(status)) return Route.BUFFERED;
        if (hasOutstandingExecution) return Route.DEFERRED_REPLAN;
        return "PAUSED".equals(status) ? Route.PAUSED_REPLAN : Route.QUIESCENT_REPLAN;
    }

    public enum Route { BUFFERED, DEFERRED_REPLAN, PAUSED_REPLAN, QUIESCENT_REPLAN }
}
