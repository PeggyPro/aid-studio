package com.aid.assistant.budget;

import com.aid.assistant.dto.AssistantRequests;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Canonical operation scope checked both when scheduling and before Provider submission. */
@Data
public class AssistantBudgetOperationScope
{
    private String operationKind;
    private String toolCode;
    private String modelCode;
    private List<String> capabilityCodes = new ArrayList<>();
    private List<String> mediaTypes = new ArrayList<>();
    private List<AssistantRequests.ContextRef> targetRefs = new ArrayList<>();
    private BigDecimal quotedUpperBound;
    private boolean countsAsIteration;
}
