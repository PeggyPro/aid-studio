package com.aid.generation.service;

import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;

/** Java 内部调用通用生成门面的可信上下文。 */
@Value
@Builder
public class GenerationInvocationContext {
    Long userId;
    String systemPrompt;
    Boolean structuredOutputEnabled;
    String businessFuncCode;
    Long bizTaskId;
    String bizTaskType;
    String callId;
    String callIdentity;
    Boolean billingExempt;
    BigDecimal authorizedPreHoldCap;

    public static GenerationInvocationContext user(Long userId) {
        return GenerationInvocationContext.builder().userId(userId).build();
    }
}
