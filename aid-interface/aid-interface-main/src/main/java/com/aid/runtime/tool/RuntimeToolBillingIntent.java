package com.aid.runtime.tool;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/** Trusted quote and normalized request identity produced without creating a task. */
@Data
public class RuntimeToolBillingIntent
{
    private String mediaType;
    private String normalizedRequestJson;
    private String requestDigest;
    private String quoteId;
    private String quoteDigest;
    private Date quoteExpiresAt;
    private BigDecimal quotedUpperBound;
}
