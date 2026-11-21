package com.aid.runtime.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Live, read-only execution choices a Tool can quote for the current trusted scope. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeToolBudgetOffer
{
    private List<String> modelCodes = new ArrayList<>();
    private List<String> capabilityCodes = new ArrayList<>();
    private List<String> mediaTypes = new ArrayList<>();
    private List<ModelChoice> modelChoices = new ArrayList<>();

    public RuntimeToolBudgetOffer(List<String> modelCodes, List<String> capabilityCodes, List<String> mediaTypes)
    {
        this.modelCodes = modelCodes;
        this.capabilityCodes = capabilityCodes;
        this.mediaTypes = mediaTypes;
    }

    /** Published user-catalog parameters only; no routes, provider secrets or billing authority. */
    public record ModelChoice(String modelCode, String capabilityCode, String generateMode, Boolean free,
                              List<com.aid.aid.domain.model.ModelParameter> parameterSchema,
                              List<com.aid.aid.domain.model.ModelParameterRule> parameterRules)
    {
    }
}
