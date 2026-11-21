package com.aid.billing.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Date;

/** 服务端为真实媒体调用附加的Run预算引用。 */
@Data
public class BillingBudgetContext {

    public static final String MODE_RUN_AUTHORIZATION = "RUN_AUTHORIZATION";
    public static final String ASSISTANT_BIZ_TASK_TYPE = "ASSISTANT_RUNTIME_STEP";
    public static final String SKILL_BIZ_TASK_TYPE = "SKILL_RUNTIME_STEP";

    public static boolean isBudgetManagedBizTaskType(String value) {
        return ASSISTANT_BIZ_TASK_TYPE.equals(value) || SKILL_BIZ_TASK_TYPE.equals(value);
    }

    private String mode;
    private Long authorizationId;
    private Long authorizationRevision;
    private Long reservationId;
    private Long reservationRevision;
    private Long runtimeRunId;
    private Long runtimeStepId;
    /** Runtime and child-step fences captured by the worker that creates the first durable media intent. */
    private Long runtimeRunFencingToken;
    private Long runtimeStepFencingToken;
    private String stableStepKey;
    private Integer attemptNo;
    private String logicalCallKey;
    private String requestDigest;
    private String quoteId;
    private String quoteDigest;
    private Date quoteExpiresAt;

    /** 只允许服务端代码构造，不接受JSON载荷覆盖。 */
    @JsonIgnore
    @JSONField(serialize = false, deserialize = false)
    public boolean isRunAuthorizationMode() {
        return MODE_RUN_AUTHORIZATION.equals(mode);
    }
}
