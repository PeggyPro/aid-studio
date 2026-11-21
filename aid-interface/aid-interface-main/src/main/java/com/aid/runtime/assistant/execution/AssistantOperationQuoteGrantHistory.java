package com.aid.runtime.assistant.execution;

import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.domain.AidRuntimeRun;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Pure bounded proof of the user's explicit grant revisions; never issues or upgrades a grant. */
public final class AssistantOperationQuoteGrantHistory {
    public static final int MAX_COMMANDS = 200;
    private AssistantOperationQuoteGrantHistory() { }

    /** Commands are only the exact current run/auth revision interval, never session lifetime history. */
    public static void requireBudgetRepriceSource(AidRuntimeRun run, AidRuntimeApproval source,
            Long currentAuthorizationId, Long currentAuthorizationRevision, String currentPolicyScopeDigest,
            List<AidRuntimeCommand> commands) {
        require(run != null && source != null && "0".equals(source.getDelFlag())
                && Objects.equals(source.getRunId(), run.getId()) && Objects.equals(source.getUserId(), run.getUserId())
                && "INVALIDATED".equals(source.getStatus()) && source.getInvalidatedAt() != null
                && source.getAcceptedAt() == null && "PLAN_EXECUTION".equals(source.getScopeKind())
                && source.getBillingAuthorizationId() != null && source.getBillingAuthorizationRevision() != null
                && Objects.equals(source.getBillingAuthorizationId(), currentAuthorizationId)
                && currentAuthorizationRevision != null
                && currentAuthorizationRevision > source.getBillingAuthorizationRevision()
                && run.getStateRevision() != null && commands != null && commands.size() <= MAX_COMMANDS
                && commands.stream().allMatch(Objects::nonNull), "OPERATION_QUOTE_LINEAGE_INVALID");
        long cursor = source.getBillingAuthorizationRevision();
        long lastRunRevision = -1;
        boolean started = false;
        String finalScopeDigest = null;
        var ids = new HashSet<Long>();
        for (AidRuntimeCommand command : commands.stream().sorted(Comparator.comparing(AidRuntimeCommand::getId,
                Comparator.nullsFirst(Long::compareTo))).toList()) {
            require(command != null && command.getId() != null && command.getId() > 0 && ids.add(command.getId())
                    && "0".equals(command.getDelFlag()) && Objects.equals(command.getActorId(), run.getUserId())
                    && Objects.equals(command.getSessionId(), run.getSessionId())
                    && "BUDGET_REVISE".equals(command.getCommandType()) && "COMPLETED".equals(command.getStatus())
                    && command.getPayloadDigest() != null && command.getPayloadDigest().matches("[0-9a-f]{64}")
                    && command.getResultJson() != null && command.getResultJson().length() <= 2_000_000,
                    "OPERATION_QUOTE_LINEAGE_INVALID");
            JSONObject result;
            try { result = JSON.parseObject(command.getResultJson()); }
            catch (RuntimeException invalid) { throw new ServiceException("OPERATION_QUOTE_LINEAGE_INVALID"); }
            JSONObject policy = result == null ? null : result.getJSONObject("budgetPolicy");
            require(policy != null, "OPERATION_QUOTE_LINEAGE_INVALID");
            String resultRunId = decimalId(policy.get("runId"));
            if (!Objects.equals(resultRunId, String.valueOf(run.getId()))) continue;
            String authorizationId = decimalId(policy.get("authorizationId"));
            if (!Objects.equals(authorizationId, String.valueOf(currentAuthorizationId))) continue;
            long revision = integral(policy.get("authorizationRevision"));
            if (revision <= cursor) continue;
            require(revision == cursor + 1 && revision <= currentAuthorizationRevision,
                    "OPERATION_QUOTE_LINEAGE_INVALID");
            long runRevision = integral(result.get("runRevision"));
            require(runRevision > lastRunRevision && runRevision <= run.getStateRevision()
                    && Objects.equals(policy.get("runRevision"), result.get("runRevision"))
                    && result.get("renewedExpired") instanceof Boolean,
                    "OPERATION_QUOTE_LINEAGE_INVALID");
            if (!started) {
                var invalidated = result.getJSONArray("invalidatedApprovalIds");
                require(invalidated != null && invalidated.stream().allMatch(value -> value instanceof String)
                        && invalidated.contains(String.valueOf(source.getId())), "OPERATION_QUOTE_LINEAGE_INVALID");
                started = true;
            }
            cursor = revision;lastRunRevision = runRevision;
            finalScopeDigest = policy.getString("scopeDigest");
        }
        require(started && cursor == currentAuthorizationRevision
                && Objects.equals(finalScopeDigest, currentPolicyScopeDigest), "OPERATION_QUOTE_LINEAGE_INVALID");
    }

    private static String decimalId(Object value) {
        require(value instanceof String text && text.matches("[1-9][0-9]{0,18}"), "OPERATION_QUOTE_LINEAGE_INVALID");
        return (String)value;
    }
    private static long integral(Object value) {
        require(value instanceof Long number && number >= 0 || value instanceof Integer integer && integer >= 0,
                "OPERATION_QUOTE_LINEAGE_INVALID");
        return ((Number)value).longValue();
    }
    private static void require(boolean condition, String code) {
        if (!condition) throw new ServiceException(code);
    }
}
