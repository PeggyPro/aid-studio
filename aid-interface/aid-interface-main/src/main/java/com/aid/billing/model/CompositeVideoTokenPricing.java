package com.aid.billing.model;

import java.math.BigDecimal;
import lombok.Data;

/** Optional token components on the single matched PER_SECOND video SKU; prices use the rule currency. */
@Data
public class CompositeVideoTokenPricing {
    private BigDecimal inputPricePerMillion;
    private BigDecimal textOutputPricePerMillion;
    private BigDecimal reasoningPricePerMillion;
    private BigDecimal videoOutputPricePerMillion;
    private BigDecimal videoTokensPerSecond;
    private Integer estimatedTextOutputTokens;
    private Integer estimatedReasoningTokens;
}
