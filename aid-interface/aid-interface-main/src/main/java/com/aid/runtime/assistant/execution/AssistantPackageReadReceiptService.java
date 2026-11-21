package com.aid.runtime.assistant.execution;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.tool.AgentPackageRuntimeToolProvider;
import com.aid.runtime.tool.SkillRuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Verified, bounded package-resource observations from the current run and unchanged context. */
@Component
@RequiredArgsConstructor
public class AssistantPackageReadReceiptService {
    private static final int MAX_PACKAGE_READS = 1_000;

    private final AidRuntimeRunStepMapper steps;
    private final AssistantCompletedToolScopeVerifier completedToolScopes;

    public List<VerifiedPackageRead> currentContextReads(Long userId, AidRuntimeRun run) {
        return contextReadsBefore(userId, run, null);
    }

    public List<VerifiedPackageRead> contextReadsBefore(Long userId, AidRuntimeRun run, Long stepIdExclusive) {
        if (userId == null || userId <= 0 || run == null || run.getId() == null
                || !Objects.equals(userId, run.getUserId()) || run.getPlanRevision() == null) {
            throw invalid("PACKAGE_READ_RECEIPT_RUN_INVALID");
        }
        var query = Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .le(AidRuntimeRunStep::getPlanRevision, run.getPlanRevision())
                .eq(AidRuntimeRunStep::getStepKind, "TOOL")
                .in(AidRuntimeRunStep::getToolCode,
                        SkillRuntimeToolProvider.READ_RESOURCE,
                        AgentPackageRuntimeToolProvider.READ_RESOURCE)
                .eq(AidRuntimeRunStep::getStatus, "SUCCEEDED")
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByAsc(AidRuntimeRunStep::getPlanRevision)
                .orderByAsc(AidRuntimeRunStep::getPlanPosition)
                .orderByAsc(AidRuntimeRunStep::getAttemptNo)
                .orderByAsc(AidRuntimeRunStep::getId);
        if (stepIdExclusive != null) query.lt(AidRuntimeRunStep::getId, stepIdExclusive);
        query.last("LIMIT " + (MAX_PACKAGE_READS + 1));
        List<AidRuntimeRunStep> rows = steps.selectList(query);
        if (rows == null) return List.of();
        if (rows.size() > MAX_PACKAGE_READS)
            throw invalid("PACKAGE_READ_RECEIPT_HISTORY_TOO_LARGE");
        List<VerifiedPackageRead> result = new ArrayList<>(rows.size());
        for (AidRuntimeRunStep row : rows) {
            if (!completedToolScopes.belongsToCurrentContext(userId, run, row)) continue;
            result.add(verify(userId, run, row));
        }
        return List.copyOf(result);
    }

    private VerifiedPackageRead verify(Long userId, AidRuntimeRun run, AidRuntimeRunStep step) {
        final com.aid.assistant.effect.VerifiedRuntimeToolEffect effect;
        try {
            effect = completedToolScopes.require(userId, run, step);
        } catch (ServiceException invalid) {
            throw invalid("PACKAGE_READ_RECEIPT_SCOPE_INVALID");
        }
        JSONObject arguments = object(effect.argumentsJson());
        JSONObject output = object(effect.outputJson());
        String toolCode = effect.toolCode();
        String path = arguments.getString("path");
        String content = output.getString("content");
        String packageDigest = output.getString("packageDigest");
        String resourceDigest = output.getString("resourceDigest");
        require(Objects.equals(path, output.getString("path"))
                        && StrUtil.isNotBlank(path) && content != null
                        && packageDigest != null && packageDigest.matches("^[0-9a-f]{64}$")
                        && resourceDigest != null && resourceDigest.matches("^[0-9a-f]{64}$")
                        && Objects.equals(resourceDigest, SecureUtil.sha256(content)),
                "PACKAGE_READ_RECEIPT_OUTPUT_INVALID");

        JSONObject normalized = new JSONObject();
        if (SkillRuntimeToolProvider.READ_RESOURCE.equals(toolCode)) {
            String skillCode = arguments.getString("skillCode");
            Long versionId = arguments.getLong("versionId");
            require(StrUtil.isNotBlank(skillCode) && versionId != null && versionId > 0
                            && Objects.equals(skillCode, output.getString("skillCode"))
                            && Objects.equals(versionId, output.getLong("skillVersionId")),
                    "PACKAGE_READ_RECEIPT_IDENTITY_INVALID");
            normalized.put("skillCode", skillCode);
            normalized.put("skillVersionId", versionId);
        } else if (AgentPackageRuntimeToolProvider.READ_RESOURCE.equals(toolCode)) {
            Long versionId = output.getLong("agentVersionId");
            require(versionId != null && versionId > 0
                            && Objects.equals(versionId, run.getVersionId())
                            && Objects.equals(packageDigest, run.getVersionDigest()),
                    "PACKAGE_READ_RECEIPT_IDENTITY_INVALID");
            normalized.put("agentVersionId", versionId);
            if ("SP.md".equals(path) && output.getJSONArray("resourcePaths") != null)
                normalized.put("resourcePaths", new ArrayList<>(output.getJSONArray("resourcePaths")));
        } else {
            throw invalid("PACKAGE_READ_RECEIPT_TOOL_INVALID");
        }
        normalized.put("packageDigest", packageDigest);
        normalized.put("path", path);
        normalized.put("resourceDigest", resourceDigest);
        normalized.put("content", content);
        return new VerifiedPackageRead(step.getId(), step.getStableStepId(), step.getAttemptNo(),
                toolCode, effect.contractVersion(), effect.schemaDigest(), normalized);
    }

    private JSONObject object(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw invalid("PACKAGE_READ_RECEIPT_JSON_INVALID");
        }
    }

    private void require(boolean valid, String code) {
        if (!valid) throw invalid(code);
    }

    private InvalidReceiptException invalid(String code) {
        return new InvalidReceiptException(code);
    }

    public static final class InvalidReceiptException extends RuntimeException {
        public InvalidReceiptException(String code) { super(code); }
    }

    public record VerifiedPackageRead(Long stepId, String stableStepId, Integer attemptNo,
                                      String toolCode, String contractVersion, String schemaDigest,
                                      JSONObject observation) {
        public String identity() {
            String packageIdentity = SkillRuntimeToolProvider.READ_RESOURCE.equals(toolCode)
                    ? observation.getString("skillCode") + ":" + observation.getString("skillVersionId")
                    : String.valueOf(observation.getLong("agentVersionId"));
            return toolCode + "\n" + packageIdentity + "\n"
                    + observation.getString("packageDigest") + "\n" + observation.getString("path");
        }
    }
}
