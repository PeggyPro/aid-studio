package com.aid.runtime.assistant;

import com.aid.runtime.tool.RuntimeCapabilityInputSchema;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uses only host-verified model discovery receipts, never message text or model-generated metadata. */
final class NativeCapabilityInputProjection {
    private NativeCapabilityInputProjection() { }

    static boolean hasVerifiedContract(JSONObject request, String toolCode) {
        Map<String, JSONObject> contracts = new LinkedHashMap<>();
        String selected = selectedModel(request, toolCode);
        collect(request.getJSONArray("observations"), toolCode, true, selected, contracts);
        collect(request.getJSONArray("priorResults"), toolCode, false, selected, contracts);
        return !contracts.isEmpty();
    }

    static JSONObject project(JSONObject request, String toolCode, JSONObject source) {
        return project(request, toolCode, source, null);
    }

    static JSONObject project(JSONObject request, String toolCode, JSONObject source, String registeredMediaType) {
        return project(request, toolCode, source, registeredMediaType, java.util.Set.of());
    }

    static JSONObject project(JSONObject request, String toolCode, JSONObject source, String registeredMediaType,
                              java.util.Set<String> independentFields) {
        Map<String,JSONObject> contracts = new LinkedHashMap<>();
        String selected = registeredMediaType == null ? selectedModel(request, toolCode)
                : selectedModelOfType(request, registeredMediaType);
        collect(request.getJSONArray("observations"), toolCode, true, selected, contracts);
        collect(request.getJSONArray("priorResults"), toolCode, false, selected, contracts);
        // A composer preference is not a verified capability contract for this Tool.
        // Without a matching receipt, retain its published model parameter. Normal
        // preparation still enforces the frozen model choice and the Tool's allowed models.
        if (contracts.isEmpty()) return source;
        JSONArray alternatives = new JSONArray();
        for (JSONObject support : contracts.values()) {
            JSONObject capabilities = support.getJSONObject("capabilities");
            for (String capability : capabilities.keySet()) {
                if (alternatives.size() >= 32) throw new IllegalArgumentException("Too many resolved capability contracts");
                alternatives.add(RuntimeCapabilityInputSchema.bind(source, support.getString("modelCode"),
                        capability, capabilities.getJSONObject(capability), independentFields));
            }
        }
        if (alternatives.size() == 1) return alternatives.getJSONObject(0);
        JSONObject result = new JSONObject(); result.put("type", "object"); result.put("oneOf", alternatives);
        return result;
    }

    /** Same trusted receipt validation used by executable media Tool contracts. */
    static Map<String, JSONObject> nodeContracts(JSONObject request, String toolCode, String mediaType) {
        Map<String, JSONObject> contracts = new LinkedHashMap<>();
        String selected = selectedModelOfType(request, mediaType);
        collect(request.getJSONArray("observations"), toolCode, true, selected, contracts, true);
        collect(request.getJSONArray("priorResults"), toolCode, false, selected, contracts, true);
        return contracts;
    }

    /** Explicit composer choices take precedence over historical discovery, without loading a model list. */
    private static String selectedModelOfType(JSONObject request, String mediaType) {
        JSONObject turn = request.getJSONObject("turn");
        JSONObject original = turn == null ? null : turn.getJSONObject("originalRequest");
        JSONArray models = original == null ? null : original.getJSONArray("selectedModels");
        String selected = null;
        if (models != null) for (Object raw : models) {
            if (!(raw instanceof JSONObject model) || !mediaType.equalsIgnoreCase(model.getString("modelType"))) continue;
            String code = model.getString("modelCode");
            if (code == null || code.isBlank() || selected != null && !selected.equals(code))
                throw new IllegalArgumentException("Invalid fixed model selection");
            selected = code;
        }
        return selected;
    }

    private static String selectedModel(JSONObject request, String toolCode) {
        JSONObject turn = request.getJSONObject("turn");
        JSONObject original = turn == null ? null : turn.getJSONObject("originalRequest");
        JSONArray selected = original == null ? null : original.getJSONArray("selectedModels");
        JSONArray tools = request.getJSONArray("toolCatalog");
        if (selected == null || tools == null) return null;
        for (Object raw : tools) {
            if (!(raw instanceof JSONObject tool) || !toolCode.equals(tool.getString("toolCode"))) continue;
            JSONObject options = tool.getJSONObject("executionOptions");
            JSONArray mediaTypes = options == null ? null : options.getJSONArray("mediaTypes");
            if (mediaTypes == null) return null;
            for (Object choice : selected) {
                if (!(choice instanceof JSONObject model) || model.getString("modelCode") == null) continue;
                String type = model.getString("modelType");
                if (type != null && mediaTypes.stream().anyMatch(value -> type.equalsIgnoreCase(String.valueOf(value))))
                    return model.getString("modelCode");
            }
        }
        return null;
    }

    private static boolean nodeToolMatches(String expected, String actual) {
        if (com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.IMAGE_GENERATE_WITH_PARAMETERS.equals(expected))
            return actual != null && java.util.Set.of(
                    com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.IMAGE_GENERATE,
                    com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.IMAGE_GENERATE_WITH_PARAMETERS,
                    com.aid.canvas.runtime.CanvasAssistantMediaToolProvider.IMAGE_GENERATE_WITH_SKILL).contains(actual);
        return expected.equals(actual);
    }

    private static void collect(JSONArray receipts, String toolCode, boolean verifiedContext, String selected,
                                Map<String,JSONObject> contracts) {
        collect(receipts, toolCode, verifiedContext, selected, contracts, false);
    }

    private static void collect(JSONArray receipts, String toolCode, boolean verifiedContext, String selected,
                                Map<String,JSONObject> contracts, boolean nodeParameters) {
        if (receipts == null) return;
        for (int i=0;i<receipts.size();i++) {
            JSONObject receipt=receipts.getJSONObject(i);
            if (receipt==null || !"aid.model.query-target".equals(receipt.getString("toolCode")))continue;
            if (receipt.containsKey("status") && !"SUCCEEDED".equals(receipt.getString("status")))continue;
            if (verifiedContext ? !"SERVER_VERIFIED_RUN_CONTEXT_RECEIPT".equals(receipt.getString("authority"))
                    : !"SUCCEEDED".equals(receipt.getString("status")))continue;
            JSONObject observation=receipt.getJSONObject("observation");
            if (observation != null && observation.containsKey("schemaVersion")) {
                if (!com.aid.assistant.service.ModelCatalogObservationProjector.SCHEMA_VERSION.equals(observation.getString("schemaVersion"))
                        || !receipt.getString("toolCode").equals(observation.getString("toolCode"))
                        || receipt.getString("stepId") != null && !receipt.getString("stepId").equals(observation.getString("sourceStepId")))
                    throw new IllegalArgumentException("Model observation identity mismatch");
                observation = observation.getJSONObject("page");
            }
            JSONObject detail=observation==null?null:observation.getJSONObject("detail");
            if(detail==null || !"TOOL".equals(detail.getString("targetKind"))
                    || !(nodeParameters ? nodeToolMatches(toolCode, detail.getString("toolCode"))
                    : toolCode.equals(detail.getString("toolCode")))
                    || !"AVAILABLE".equals(detail.getString("lookupStatus")))continue;
            JSONObject support=detail.getJSONObject("toolInputSupport");
            if(support==null || support.getString("modelCode")==null || support.getString("providerCode")==null
                    || support.getJSONObject("capabilities")==null || support.getJSONObject("capabilities").isEmpty())continue;
            if (selected != null && !selected.equals(support.getString("modelCode"))) continue;
            if(contracts.size()>=8 && !contracts.containsKey(support.getString("modelCode")))
                throw new IllegalArgumentException("Too many resolved model contracts");
            String modelCode = support.getString("modelCode");
            String capabilityScope = detail.getString("capabilityCode");
            String catalogScope = detail.getString("toolInputSupportScope");
            if (catalogScope != null && !java.util.Set.of("MODEL", "CAPABILITIES").contains(catalogScope))
                throw new IllegalArgumentException("Model capability catalog scope invalid");
            JSONObject incoming = com.alibaba.fastjson2.JSON.parseObject(support.toJSONString());
            if (nodeParameters) {
                JSONObject capability = detail.getJSONObject("capability");
                if (capability != null && capability.getJSONArray("parameterSchema") != null) {
                    String code = capability.getString("capabilityCode");
                    JSONObject target = incoming.getJSONObject("capabilities").getJSONObject(code);
                    if (target != null) target.put("nodeParameterSchema", capability.getJSONArray("parameterSchema"));
                }
            }
            JSONObject previous = contracts.get(modelCode);
            // A capability-scoped DETAIL is a partial observation, not a replacement model
            // catalog. Otherwise reading image_to_image after text_to_image makes the host
            // incorrectly freeze the sole remaining capability into every invocation.
            if (!"MODEL".equals(catalogScope)) {
                JSONObject observed = incoming.getJSONObject("capabilities");
                if (capabilityScope != null && !capabilityScope.isBlank()
                        && !observed.containsKey(capabilityScope))
                    throw new IllegalArgumentException("Model capability observation scope mismatch");
                if (previous != null && java.util.Objects.equals(previous.getString("providerCode"),
                        incoming.getString("providerCode"))) {
                    JSONObject merged = com.alibaba.fastjson2.JSON.parseObject(
                            previous.getJSONObject("capabilities").toJSONString());
                    merged.putAll(observed);
                    incoming.put("capabilities", merged);
                }
            }
            // Only an explicitly complete MODEL receipt replaces the catalog: legacy DETAIL
            // receipts also used generateMode filters but did not record their scope.
            // A provider change starts a new catalog. Never mutate archived receipts.
            contracts.put(modelCode, incoming);
        }
    }
}
