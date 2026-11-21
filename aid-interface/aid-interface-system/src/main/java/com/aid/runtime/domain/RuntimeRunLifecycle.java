package com.aid.runtime.domain;

import java.util.List;

/** Terminal states of a durable runtime run, shared by decisions and persistence queries. */
public final class RuntimeRunLifecycle {
    public static final List<String> TERMINAL_STATUSES = List.of(
            "SUCCEEDED", "COMPLETED", "FAILED", "STOPPED", "CANCELLED", "CANCELED");

    private RuntimeRunLifecycle() { }

    public static boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }
}
