package com.aid.runtime.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.common.exception.ServiceException;
import com.aid.model.dto.AiModelListRequest;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.model.vo.ModelCapabilityOptionVO;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.assistant.AssistantModuleAgentResolver;
import com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only, user-visible model discovery for an authenticated Assistant run. */
@Component
@RequiredArgsConstructor
public class AssistantModelCatalogRuntimeToolProvider implements RuntimeToolProvider {
    public static final String QUERY = "aid.model.query";
    public static final String DETAIL = "aid.model.detail";
    public static final String SCOPED_QUERY = "aid.model.query-scoped";
    public static final String SCOPED_QUERY_V2 = "aid.model.query-target";
    private static final String VERSION = "1.0.0";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PARAMETERS = 64;
    private static final int MAX_PARAMETER_RULES = 64;
    private static final int MAX_BILLING_COLUMNS = 32;
    private static final int MAX_BILLING_RULES = 100;
    private static final int MAX_CAPABILITY_CODES = 64;
    private static final int MAX_DETAIL_UTF8_BYTES = 65_536;
    private static final Set<String> MODEL_TYPES = Set.of("text", "image", "video", "audio");
    private static final Map<String, String> GENERATION_TOOL_MODEL_TYPES = Map.of(
            "media.image.generate", "image",
            "media.video.generate", "video",
            "media.audio.generate", "audio",
            "media.music.generate", "audio");

    private static final String INPUT = """
            {"type":"object","required":["operation"],"additionalProperties":false,"properties":{
              "operation":{"type":"string","enum":["LIST","DETAIL"]},
              "modelType":{"type":["string","null"],"enum":["text","image","video","audio",null]},
              "generateMode":{"type":["string","null"],"pattern":"^[a-z][a-z0-9_]{0,63}$"},
              "keyword":{"type":["string","null"],"maxLength":100},
              "page":{"type":["integer","null"],"minimum":1,"maximum":10000},
              "pageSize":{"type":["integer","null"],"minimum":1,"maximum":20},
              "modelCode":{"type":["string","null"],"minLength":1,"maxLength":100},
              "providerCode":{"type":["string","null"],"minLength":1,"maxLength":100},
              "providerName":{"type":["string","null"],"minLength":1,"maxLength":100}
            }}""";
    private static final String DETAIL_INPUT = """
            {"type":"object","required":["modelCode","providerCode","generateMode"],"additionalProperties":false,"properties":{
              "modelCode":{"type":"string","minLength":1,"maxLength":100,"pattern":"^\\\\S+$"},
              "providerCode":{"type":"string","minLength":1,"maxLength":100,"pattern":"^\\\\S+$"},
              "generateMode":{"type":"string","pattern":"^[a-z][a-z0-9_]{0,63}$"}
            }}""";
    private static final String OUTPUT = """
            {"type":"object","required":["operation","items","total","page","pageSize","hasMore","detail"],"additionalProperties":false,"properties":{
              "operation":{"type":"string","enum":["LIST","DETAIL"]},
              "items":{"type":"array","maxItems":20,"items":{"type":"object","required":["modelCode","modelName","modelType","providerCode","providerName","capabilityCodes","selectionScope"],"additionalProperties":false,"properties":{
                "modelCode":{"type":"string"},"modelName":{"type":"string"},"modelType":{"type":"string"},
                "generateMode":{"type":["string","null"]},"providerCode":{"type":"string"},"providerName":{"type":"string"},
                "capabilityCodes":{"type":"array","items":{"type":"string"}},
                "selectionScope":{"type":"string","enum":["AGENT_FIXED_CANDIDATE","GENERATION_TOOL"]}
              }}},
              "total":{"type":"integer","minimum":0},"page":{"type":"integer","minimum":1},
              "pageSize":{"type":"integer","minimum":1,"maximum":20},"hasMore":{"type":"boolean"},
              "detail":{"type":["object","null"]}
            }}""";

    private static final String SCOPED_INPUT = scopedInput();
    private static final String SCOPED_OUTPUT = OUTPUT.replace(
            "\"AGENT_FIXED_CANDIDATE\",\"GENERATION_TOOL\"",
            "\"AGENT_FIXED_CANDIDATE\",\"GENERATION_TOOL\",\"SKILL_FIXED_CANDIDATE\"");
    @Resource private AssistantSkillModelScopeResolver skillModelScopes;
    @Resource @Lazy private RuntimeToolRegistry toolRegistry;
    private final IAiModelBusinessService modelBusinessService;
    private final AidRuntimeRunMapper runs;
    private final AidAssistantSessionMapper sessions;
    private final AidRuntimeAgentToolRefMapper agentToolRefs;
    private final AssistantModuleAgentResolver moduleAgents;

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return catalogDescriptors();
    }

    public static RuntimeToolRegistry.Descriptor descriptorForCode(String code) {
        return catalogDescriptors().stream().filter(value -> value.code().equals(code)).findFirst()
                .orElseThrow(() -> new ServiceException("MODEL_CATALOG_TOOL_UNSUPPORTED"));
    }

    public static boolean isCatalogCode(String code) {
        return QUERY.equals(code) || DETAIL.equals(code) || SCOPED_QUERY.equals(code) || SCOPED_QUERY_V2.equals(code);
    }

    private static List<RuntimeToolRegistry.Descriptor> catalogDescriptors() {
        return List.of(new RuntimeToolRegistry.Descriptor(QUERY, VERSION,
                SecureUtil.sha256(INPUT + "\n" + OUTPUT), "Query available models",
                "Query only the real model catalog authorized by this fixed Agent. For a user's model name or shorthand, first call LIST with keyword; then copy modelCode/providerCode from its returned entry and select the appropriate generateMode from detail.capabilityOptions for DETAIL. Never invent codes or use a display name as modelCode. DETAIL returns lookupStatus in detail: AVAILABLE includes bounded public parameters and billing; NOT_FOUND or AMBIGUOUS includes reasonCode, message and nextQuery for a new LIST, without selecting or generating a replacement. Text entries are fixed Agent candidates and cannot change the current run. Generation still requires the media Tool's authoritative quote and budget admission.",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE",
                List.of("model.catalog")),
                new RuntimeToolRegistry.Descriptor(DETAIL, VERSION,
                        SecureUtil.sha256(DETAIL_INPUT + "\n" + OUTPUT), "Read one available model",
                        "Read one exact model from the real catalog authorized by this fixed Agent. Copy modelCode/providerCode and the intent-matched generateMode from a current LIST result and its detail.capabilityOptions. Never invent codes, reuse an old result without rechecking, or use a display name as a stable code. AVAILABLE includes bounded public parameters and billing; NOT_FOUND or AMBIGUOUS is a read observation that directs a new LIST without selecting or generating a replacement. Generation still requires the media Tool's authoritative quote and budget admission.",
                        DETAIL_INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE",
                        List.of("model.catalog")),
                new RuntimeToolRegistry.Descriptor(SCOPED_QUERY_V2, VERSION,
                        SecureUtil.sha256(scopedNullableInput() + "\n" + SCOPED_OUTPUT), "查询目标模型（可选空值契约）",
                        "仅按用户询问或指定的目标查询。默认模型仅认defaultModelCode配置，不认列表顺序。AGENT不带skillCode/toolCode；SKILL只带skillCode；TOOL只带toolCode。可选参数的JSON null明确表示未指定，必要参数不可为空；不猜测、不改写模型或业务值。DETAIL使用已核实的modelCode/providerCode及detail.capabilityOptions中符合需求与素材的generateMode，不把items展示模式当作已选择模式。LIST仅在需要匹配或选择候选时使用。",
                        scopedNullableInput(), SCOPED_OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE",
                        List.of("model.catalog")),
                new RuntimeToolRegistry.Descriptor(SCOPED_QUERY, VERSION,
                        SecureUtil.sha256(SCOPED_INPUT + "\n" + SCOPED_OUTPUT), "查询当前目标的模型",
                        "仅在用户询问、指定模型或需要替代方案时调用；普通执行直接使用各自默认模型。targetKind=AGENT查询当前固定智能体文本候选；SKILL须指定skillCode，只查询该授权Skill固定版本的模型；TOOL须指定已绑定的媒体生成toolCode，只查询对应媒体类型；toolCode须使用绑定的业务编码，不能拼接或猜测函数名。NEEDS_TARGET只提供已授权目标编码供重新查询，不表示已选中或执行。LIST按名称或厂商关键词匹配；不完全一致时请用户确认，不擅自替换。DETAIL必须携带modelCode、providerCode；已知这两个标识时直接定向查询，不重复LIST。generateMode未知可留空，按返回options选择符合需求与素材的真实模式再定向DETAIL；禁止猜测生成模式或机械沿用items展示模式。NEEDS_IDENTIFIERS表示查询参数不足，按nextQuery补查，不代表模型不可用或生图失败。不要用其他Skill或智能体的模型池代替当前目标。",
                        SCOPED_INPUT, SCOPED_OUTPUT, "READ", false, "REQUEST_DIGEST", false,
                        "REPLAY_SAFE", List.of("model.catalog")));
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor) {
        return context != null && context.getUserId() != null && context.getUserId() > 0
                ? RuntimeToolAvailability.enabled()
                : RuntimeToolAvailability.unavailable("ASSISTANT_MODEL_CATALOG_IDENTITY_REQUIRED");
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return OutputReferenceKind.OBSERVATION;
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        JSONObject arguments = object(invocation == null ? null : invocation.getArgumentsJson());
        String operation = DETAIL.equals(invocation.getToolCode())
                ? "DETAIL" : arguments.getString("operation");
        if (!isScopedQuery(invocation.getToolCode()) && "DETAIL".equals(operation) && (StrUtil.isBlank(arguments.getString("modelCode"))
                || StrUtil.isBlank(arguments.getString("providerCode"))
                || StrUtil.isBlank(arguments.getString("generateMode")))) {
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_REQUIRED);
        }
        return RuntimeToolProvider.super.prepare(invocation);
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        if (invocation != null && isScopedQuery(invocation.getToolCode())) return executeScoped(invocation);
        VerifiedRun verified = activeAssistantRun(invocation);
        if (verified == null)
            return failed("MODEL_CATALOG_RUN_IDENTITY_INVALID", "模型目录运行身份无效");
        JSONObject arguments = object(invocation.getArgumentsJson());
        String operation = DETAIL.equals(invocation.getToolCode())
                ? "DETAIL" : arguments.getString("operation");
        if (!List.of("LIST", "DETAIL").contains(operation))
            return failed("MODEL_CATALOG_OPERATION_INVALID", "模型目录查询操作无效");

        AiModelListRequest request = new AiModelListRequest();
        String modelType = trimToNull(arguments.getString("modelType"));
        String generateMode = trimToNull(arguments.getString("generateMode"));
        CatalogScope scope = catalogScope(verified.binding(), invocation.getToolCode());
        if (scope.modelTypes().isEmpty())
            return failed("MODEL_CATALOG_SCOPE_EMPTY", "当前固定Agent未开放可查询的模型范围");
        if (modelType != null && (!MODEL_TYPES.contains(modelType) || !scope.modelTypes().contains(modelType)))
            return failed("MODEL_CATALOG_FILTER_INVALID", "模型目录筛选条件无效");
        if ("LIST".equals(operation) && modelType == null) {
            if (scope.modelTypes().size() == 1) modelType = scope.modelTypes().iterator().next();
            else {
                JSONObject clarification = new JSONObject();
                clarification.put("lookupStatus", "NEEDS_MODEL_TYPE");
                clarification.put("modelTypes", scope.modelTypes().stream().sorted().toList());
                clarification.put("message", "请先确认要查询文本、图片、视频或音频中的哪类模型。");
                return succeeded(envelope("LIST", new JSONArray(), 0, 1, DEFAULT_PAGE_SIZE,
                        false, clarification), "需要确认模型用途");
            }
        }
        request.setModelType(modelType);
        request.setGenerateMode(generateMode);
        List<AiModelVO> available;
        if ("DETAIL".equals(operation)) {
            String exactCode = trimToNull(arguments.getString("modelCode"));
            available = exactCode == null ? List.of()
                    : modelBusinessService.listAvailableModelsByCodes(request, List.of(exactCode));
        } else if ("text".equals(modelType)) {
            available = modelBusinessService.listAvailableModelsByCodes(request,
                    scope.agentTextModelCodes().stream().sorted().toList());
        } else {
            available = modelBusinessService.listAvailableModels(request);
        }
        if (available == null) available = List.of();
        available = available.stream().filter(Objects::nonNull)
                .filter(model -> scope.modelTypes().contains(model.getModelType()))
                .filter(model -> !"text".equals(model.getModelType())
                        || scope.agentTextModelCodes().contains(model.getModelCode()))
                .filter(model -> StrUtil.isNotBlank(model.getModelCode())
                        && StrUtil.isNotBlank(model.getProviderCode())
                        && StrUtil.isNotBlank(model.getProviderName()))
                .filter(model -> model.getBilling() != null && !capabilityCodes(model).isEmpty()).toList();
        if (available.stream().anyMatch(model -> capabilityCodes(model).size() > MAX_CAPABILITY_CODES))
            return failed("MODEL_CATALOG_CAPABILITIES_TOO_LARGE",
                    "模型能力目录超过安全返回范围，请联系管理员校正目录");

        if ("DETAIL".equals(operation)) return detail(arguments, available);
        return list(arguments, available);
    }

    private RuntimeToolResult list(JSONObject arguments, List<AiModelVO> available) {
        String keyword = trimToNull(arguments.getString("keyword"));
        String normalizedKeyword = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);
        List<AiModelVO> filtered = available.stream().filter(Objects::nonNull)
                .filter(model -> StrUtil.isNotBlank(model.getModelCode()))
                .filter(model -> normalizedKeyword == null
                        || contains(model.getModelCode(), normalizedKeyword)
                        || contains(model.getModelName(), normalizedKeyword)
                        || contains(model.getProviderCode(), normalizedKeyword)
                        || contains(model.getProviderName(), normalizedKeyword)
                        || contains(model.getLegacyModelCodes(), normalizedKeyword))
                .toList();
        int page = arguments.getInteger("page") == null ? 1 : arguments.getInteger("page");
        int pageSize = arguments.getInteger("pageSize") == null
                ? DEFAULT_PAGE_SIZE : arguments.getInteger("pageSize");
        int offset = Math.min(filtered.size(), Math.multiplyExact(page - 1, pageSize));
        int end = Math.min(filtered.size(), offset + pageSize);
        JSONArray items = new JSONArray();
        JSONArray capabilityOptions = new JSONArray();
        for (AiModelVO model : filtered.subList(offset, end)) {
            items.add(summary(model));
            capabilityOptions.add(capabilitySelection(model));
        }
        JSONObject selection = new JSONObject();
        selection.put("capabilityOptions", capabilityOptions);
        selection.put("capabilitySelectionNote", "items.generateMode只是当前目录投影的模式，不代表用户已选择该模式。按用户需求和实际素材，从本页capabilityOptions选择真实capabilityCode与generateMode组合，再用对应模式定向DETAIL；不能把图生图详情套用于无参考图的文生图，也不能猜测能力编码与生成模式必然相同。详情仅证明所查询模式的参数与价格，不能据此配置其他模式。没有匹配组合时说明限制，不静默换模型或改参数。");
        if (selection.toJSONString().getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_UTF8_BYTES)
            return failed("MODEL_CATALOG_DETAIL_TOO_LARGE", "本页能力元数据超过安全返回范围，请缩小查询页大小或筛选范围");
        JSONObject output = envelope("LIST", items, filtered.size(), page, pageSize,
                end < filtered.size(), selection);
        return succeeded(output, "已查询可用模型目录");
    }

    /** Selection metadata only: reuse the already authorized page, never load parameter bodies again. */
    private JSONObject capabilitySelection(AiModelVO model) {
        JSONArray options = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (ModelCapabilityOptionVO option : model.getAvailableCapabilities() == null
                ? List.<ModelCapabilityOptionVO>of() : model.getAvailableCapabilities()) {
            if (option == null || StrUtil.isBlank(option.getCode()) || StrUtil.isBlank(option.getGenerateMode())) continue;
            if (!seen.add(option.getCode() + "\n" + option.getGenerateMode())) continue;
            options.add(JSONObject.of("capabilityCode", option.getCode(), "generateMode", option.getGenerateMode(),
                    "label", safeText(option.getLabel(), option.getCode())));
        }
        if (options.size() > MAX_CAPABILITY_CODES)
            throw new ServiceException("MODEL_CATALOG_CAPABILITIES_TOO_LARGE");
        return JSONObject.of("modelCode", model.getModelCode(), "providerCode", model.getProviderCode(), "options", options);
    }

    private RuntimeToolResult detail(JSONObject arguments, List<AiModelVO> available) {
        String requested = trimToNull(arguments.getString("modelCode"));
        String providerCode = trimToNull(arguments.getString("providerCode"));
        String providerName = trimToNull(arguments.getString("providerName"));
        String generateMode = trimToNull(arguments.getString("generateMode"));
        if (requested == null || providerCode == null)
            return failed("MODEL_CATALOG_MODEL_REQUIRED", "必须指定模型编码和供应商编码");
        List<AiModelVO> matches = available.stream().filter(Objects::nonNull)
                .filter(candidate -> Objects.equals(requested, candidate.getModelCode())
                        && Objects.equals(providerCode, candidate.getProviderCode())
                        && (providerName == null || Objects.equals(providerName, candidate.getProviderName())))
                .limit(2).toList();
        if (matches.isEmpty())
            return lookupObservation(arguments, "NOT_FOUND", "MODEL_CATALOG_MODEL_UNAVAILABLE",
                    "本次精确编码查询未匹配到当前授权目录条目；请先搜索目录，再复制返回的模型编码、供应商编码和生成模式。");
        if (matches.size() != 1)
            return lookupObservation(arguments, "AMBIGUOUS", "MODEL_CATALOG_MODEL_AMBIGUOUS",
                    "当前目录中的模型编码与供应商编码不能唯一确定条目；请重新查询目录，无法消除歧义时请管理员校正配置。");
        AiModelVO model = matches.get(0);
        if (generateMode == null) {
            JSONObject selection = capabilitySelection(model);
            selection.put("lookupStatus", "NEEDS_GENERATE_MODE");
            selection.put("reasonCode", "MODEL_CATALOG_GENERATE_MODE_REQUIRED");
            selection.put("message", "已定向核实所选模型；根据需求和素材从options选择生成模式，再查询该模型详情。无需重新列出模型目录，未选择或执行生成。");
            selection.put("nextQuery", JSONObject.of("operation", "DETAIL", "modelCode", requested,
                    "providerCode", providerCode));
            return succeeded(envelope("DETAIL", new JSONArray(), 1, 1, 1, false, selection),
                    "已读取所选模型的生成模式");
        }
        if (!Objects.equals(generateMode, model.getGenerateMode()))
            return lookupObservation(arguments, "NOT_FOUND", "MODEL_CATALOG_CAPABILITY_UNAVAILABLE",
                    "本次精确查询未匹配到所选生成模式；请重新查询目录并使用实际返回的模式，不自动更换模型。");
        List<ModelCapabilityOptionVO> selected = model.getAvailableCapabilities() == null
                ? List.of() : model.getAvailableCapabilities().stream().filter(Objects::nonNull)
                .filter(option -> Objects.equals(model.getCapabilityCode(), option.getCode())
                        && Objects.equals(generateMode, option.getGenerateMode())).limit(2).toList();
        if (selected.size() != 1)
            return lookupObservation(arguments, "AMBIGUOUS", "MODEL_CATALOG_CAPABILITY_AMBIGUOUS",
                    "当前目录中的生成模式不能唯一确定可用能力；请重新查询目录，无法消除歧义时请管理员校正配置。");
        if (!complete(selected.get(0), selected.get(0).getBilling()))
            return failed("MODEL_CATALOG_DETAIL_TOO_LARGE", "所选模型详情超过安全返回范围");
        JSONObject detail = summary(model);
        detail.put("lookupStatus", "AVAILABLE");
        detail.put("capability", capability(selected.get(0)));
        detail.put("billing", billing(selected.get(0).getBilling()));
        detail.put("quoteRequired", !"text".equals(model.getModelType()));
        detail.put("selectionMutable", !"text".equals(model.getModelType()));
        if (detail.toJSONString().getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_UTF8_BYTES)
            return failed("MODEL_CATALOG_DETAIL_TOO_LARGE", "所选模型详情超过安全返回范围");
        JSONObject output = envelope("DETAIL", new JSONArray(), 1, 1, 1, false, detail);
        return succeeded(output, "已读取所选模型详情");
    }

    /** An authorized lookup can complete with no usable match; that is data for the Agent, not an execution failure. */
    private RuntimeToolResult lookupObservation(JSONObject arguments, String lookupStatus,
                                                String reasonCode, String message) {
        JSONObject nextQuery = new JSONObject();
        nextQuery.put("operation", "LIST");
        nextQuery.put("keyword", trimToNull(arguments.getString("modelCode")));
        String modelType = trimToNull(arguments.getString("modelType"));
        if (modelType != null) nextQuery.put("modelType", modelType);
        JSONObject detail = new JSONObject();
        detail.put("lookupStatus", lookupStatus);
        detail.put("reasonCode", reasonCode);
        detail.put("message", message);
        detail.put("nextQuery", nextQuery);
        return succeeded(envelope("DETAIL", new JSONArray(), 0, 1, 1, false, detail), message);
    }

    private JSONObject summary(AiModelVO model) {
        JSONObject item = new JSONObject();
        item.put("modelCode", model.getModelCode());
        item.put("modelName", safeText(model.getModelName(), model.getModelCode()));
        item.put("modelType", safeText(model.getModelType(), "unknown"));
        item.put("generateMode", model.getGenerateMode());
        item.put("providerCode", model.getProviderCode());
        item.put("providerName", safeText(model.getProviderName(), "Unknown provider"));
        item.put("capabilityCodes", capabilityCodes(model));
        item.put("selectionScope", "text".equals(model.getModelType())
                ? "AGENT_FIXED_CANDIDATE" : "GENERATION_TOOL");
        return item;
    }

    private List<String> capabilityCodes(AiModelVO model) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(model.getCapabilityCode())) result.add(model.getCapabilityCode().trim());
        for (ModelCapabilityOptionVO capability : model.getAvailableCapabilities() == null
                ? List.<ModelCapabilityOptionVO>of() : model.getAvailableCapabilities()) {
            if (capability != null && StrUtil.isNotBlank(capability.getCode()))
                result.add(capability.getCode().trim());
        }
        return List.copyOf(result);
    }

    private JSONObject capability(ModelCapabilityOptionVO option) {
        JSONObject result = new JSONObject();
        result.put("capabilityCode", option.getCode());
        result.put("label", option.getLabel());
        result.put("generateMode", option.getGenerateMode());
        result.put("parameterSchema", array(option.getParameterSchema()));
        result.put("parameterRules", array(option.getParameterRules()));
        return result;
    }

    private JSONObject billing(Object value) {
        if (value == null) return null;
        JSONObject raw = JSON.parseObject(JSON.toJSONString(value));
        JSONObject result = new JSONObject();
        for (String key : List.of("modelCode", "modelName", "providerName", "modelType",
                "modelTypeName", "generateMode", "billingMode", "isFree", "meterType",
                "meterTypeName", "billingDesc", "creditUnit", "inputPricing")) {
            if (raw.containsKey(key)) result.put(key, raw.get(key));
        }
        result.put("columns", copy(raw.getJSONArray("columns")));
        result.put("rules", copy(raw.getJSONArray("rules")));
        return result;
    }

    private JSONArray array(List<?> values) {
        if (values == null || values.isEmpty()) return new JSONArray();
        return JSONArray.parseArray(JSON.toJSONString(values));
    }

    private JSONArray copy(JSONArray values) {
        return values == null ? new JSONArray() : JSONArray.parseArray(values.toJSONString());
    }

    private boolean complete(ModelCapabilityOptionVO option, Object billingValue) {
        if (option.getParameterSchema() != null && option.getParameterSchema().size() > MAX_PARAMETERS) return false;
        if (option.getParameterRules() != null && option.getParameterRules().size() > MAX_PARAMETER_RULES) return false;
        if (billingValue == null) return false;
        JSONObject raw = JSON.parseObject(JSON.toJSONString(billingValue));
        JSONArray columns = raw.getJSONArray("columns");
        JSONArray rules = raw.getJSONArray("rules");
        return (columns == null || columns.size() <= MAX_BILLING_COLUMNS)
                && (rules == null || rules.size() <= MAX_BILLING_RULES);
    }

    private JSONObject envelope(String operation, JSONArray items, int total, int page,
                                int pageSize, boolean hasMore, JSONObject detail) {
        JSONObject output = new JSONObject();
        output.put("operation", operation); output.put("items", items); output.put("total", total);
        output.put("page", page); output.put("pageSize", pageSize); output.put("hasMore", hasMore);
        output.put("detail", detail);
        return output;
    }

    private VerifiedRun activeAssistantRun(RuntimeToolInvocation invocation) {
        if (invocation == null || invocation.getRunId() == null || invocation.getUserId() == null) return null;
        QueryWrapper<AidRuntimeRun> runQuery = new QueryWrapper<AidRuntimeRun>().eq("id", invocation.getRunId());
        List<String> fields = new java.util.ArrayList<>(List.of("id", "user_id", "session_id", "version_id",
                "version_digest", "executor_strategy", "del_flag"));
        if (isScopedQuery(invocation.getToolCode()))
            fields.add("JSON_OBJECT('_selectedSkill', JSON_EXTRACT(request_json, '$._selectedSkill')) AS request_json");
        runQuery.select(fields.toArray(String[]::new));
        AidRuntimeRun run = runs.selectOne(runQuery);
        if (run == null || !Objects.equals(run.getUserId(), invocation.getUserId())
                || run.getSessionId() == null || run.getVersionId() == null
                || !"ASSISTANT_LOOP".equals(run.getExecutorStrategy())
                || StrUtil.isBlank(run.getVersionDigest()) || !"0".equals(run.getDelFlag()))
            return null;
        AidAssistantSession session = sessions.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .select(AidAssistantSession::getId, AidAssistantSession::getUserId, AidAssistantSession::getSessionType,
                        AidAssistantSession::getActiveRuntimeRunId, AidAssistantSession::getModuleCode,
                        AidAssistantSession::getEntryTransport, AidAssistantSession::getWorkspaceId,
                        AidAssistantSession::getDelFlag).eq(AidAssistantSession::getId, run.getSessionId()));
        if (session == null || !Objects.equals(session.getUserId(), run.getUserId())
                || !"ASSISTANT".equals(session.getSessionType())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                || StrUtil.isBlank(session.getModuleCode()) || !"0".equals(session.getDelFlag())) return null;
        try {
            AssistantLoopBinding binding = moduleAgents.requirePinned(session.getModuleCode(),
                    run.getVersionId(), run.getVersionDigest());
            return binding != null && Objects.equals(binding.getAgentVersionId(), run.getVersionId())
                    && Objects.equals(binding.getPackageDigest(), run.getVersionDigest())
                    && binding.getAgentId() != null ? new VerifiedRun(binding, session.getModuleCode(), run.getRequestJson(), com.aid.assistant.service.AssistantEntryTransport.of(session)) : null;
        } catch (ServiceException invalid) {
            return null;
        }
    }

    private CatalogScope catalogScope(AssistantLoopBinding binding, String requestedToolCode) {
        List<AidRuntimeAgentToolRef> refs = agentToolRefs.selectList(
                new LambdaQueryWrapper<AidRuntimeAgentToolRef>()
                        .select(AidRuntimeAgentToolRef::getToolCode, AidRuntimeAgentToolRef::getContractVersion,
                                AidRuntimeAgentToolRef::getSchemaDigest)
                        .eq(AidRuntimeAgentToolRef::getAgentVersionId, binding.getAgentVersionId()));
        Set<String> codes = refs == null ? Set.of() : refs.stream().filter(Objects::nonNull)
                .map(AidRuntimeAgentToolRef::getToolCode).filter(StrUtil::isNotBlank)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!codes.contains(requestedToolCode)
                || DETAIL.equals(requestedToolCode) && !codes.contains(QUERY))
            return new CatalogScope(Set.of(), Set.of(), Map.of());
        LinkedHashSet<String> modelTypes = new LinkedHashSet<>();
        Map<String, String> generationTypes = new java.util.LinkedHashMap<>();
        if (isScopedQuery(requestedToolCode)) {
            for (AidRuntimeAgentToolRef ref : refs == null ? List.<AidRuntimeAgentToolRef>of() : refs) {
                if (ref == null || StrUtil.isBlank(ref.getToolCode()) || !toolRegistry.supports(ref.getToolCode())) continue;
                RuntimeToolRegistry.Descriptor descriptor = toolRegistry.require(ref.getToolCode());
                if (!Objects.equals(ref.getContractVersion(), descriptor.contractVersion())
                        || !Objects.equals(ref.getSchemaDigest(), descriptor.schemaDigest())) continue;
                BillingMediaType media = toolRegistry.modelCatalogMediaType(ref.getToolCode());
                if (media == null || media == BillingMediaType.TEXT) continue;
                generationTypes.put(ref.getToolCode(), media == BillingMediaType.MUSIC
                        ? "audio" : media.name().toLowerCase(Locale.ROOT));
            }
        } else GENERATION_TOOL_MODEL_TYPES.forEach((toolCode, modelType) -> {
            if (codes.contains(toolCode)) generationTypes.put(toolCode, modelType);
        });
        modelTypes.addAll(generationTypes.values());
        Set<String> textModels = agentTextModelCodes(binding);
        if (!textModels.isEmpty()) modelTypes.add("text");
        return new CatalogScope(Set.copyOf(modelTypes), textModels, Map.copyOf(generationTypes));
    }

    private Set<String> agentTextModelCodes(AssistantLoopBinding binding) {
        try {
            JSONObject model = JSON.parseObject(binding.getModelConfigJson());
            if (model == null) return Set.of();
            LinkedHashSet<String> result = new LinkedHashSet<>();
            String defaultCode = trimToNull(model.getString("defaultModelCode"));
            if (defaultCode != null) result.add(defaultCode);
            JSONArray selectable = model.getJSONArray("selectableModelCodes");
            if (selectable != null) for (Object value : selectable) {
                if (!(value instanceof String code) || StrUtil.isBlank(code)) return Set.of();
                result.add(code.trim());
            }
            return Set.copyOf(result);
        } catch (RuntimeException invalid) {
            return Set.of();
        }
    }

    private record VerifiedRun(AssistantLoopBinding binding, String moduleCode, String requestJson, String transport) {}
    private record CatalogScope(Set<String> modelTypes, Set<String> agentTextModelCodes, Map<String, String> generationTypes) {}

    private static boolean isScopedQuery(String code) {
        return SCOPED_QUERY.equals(code) || SCOPED_QUERY_V2.equals(code);
    }

    private static String scopedNullableInput() {
        JSONObject schema = JSON.parseObject(scopedInput());
        JSONObject properties = schema.getJSONObject("properties");
        for (String field : new java.util.ArrayList<>(properties.keySet())) {
            if (List.of("operation", "targetKind").contains(field)) continue;
            JSONObject original = properties.getJSONObject(field);
            properties.put(field, JSONObject.of("anyOf", JSONArray.of(original, JSONObject.of("type", "null"))));
        }
        return schema.toJSONString();
    }

    private static String scopedInput() {
        JSONObject schema = JSON.parseObject(INPUT);
        schema.put("required", List.of("operation", "targetKind"));
        JSONObject properties = schema.getJSONObject("properties");
        properties.put("targetKind", JSON.parseObject("{\"type\":\"string\",\"enum\":[\"AGENT\",\"SKILL\",\"TOOL\"]}"));
        properties.put("skillCode", JSON.parseObject("{\"type\":\"string\",\"minLength\":1,\"maxLength\":64}"));
        properties.put("toolCode", JSON.parseObject("{\"type\":\"string\",\"minLength\":1,\"maxLength\":100}"));
        properties.getJSONObject("targetKind").put("description",
                "AGENT仅查询当前智能体文本模型；图片、视频、音频查询使用TOOL及其已绑定工具编码；SKILL仅查询指定Skill模型池。输入框选择图片模型不代表AGENT目标。");
        schema.put("allOf", JSON.parseArray("""
                [{"if":{"properties":{"targetKind":{"const":"AGENT"}},"required":["targetKind"]},
                  "then":{"properties":{"modelType":{"enum":["text",null]}}}}]
                """));
        return schema.toJSONString();
    }

    private RuntimeToolResult executeScoped(RuntimeToolInvocation invocation) {
        VerifiedRun verified = activeAssistantRun(invocation);
        if (verified == null) return failed("MODEL_CATALOG_RUN_IDENTITY_INVALID", "模型目录运行身份无效");
        JSONObject arguments = object(invocation.getArgumentsJson());
        String operation = arguments.getString("operation"), kind = arguments.getString("targetKind");
        if (operation == null || kind == null || !List.of("LIST", "DETAIL").contains(operation) || !List.of("AGENT", "SKILL", "TOOL").contains(kind))
            return failed("MODEL_CATALOG_TARGET_INVALID", "请先明确要查询当前智能体、指定Skill或媒体工具的模型");
        CatalogScope scope = catalogScope(verified.binding(), invocation.getToolCode());
        if (scope.modelTypes().isEmpty()) return failed("MODEL_CATALOG_SCOPE_EMPTY", "当前固定Agent未开放模型查询");
        String skillCode = trimToNull(arguments.getString("skillCode")), toolCode = trimToNull(arguments.getString("toolCode"));
        String modelType = trimToNull(arguments.getString("modelType")), defaultCode = null, requiredCapability = null;
        List<String> codes;
        if ("SKILL".equals(kind)) {
            if (skillCode == null || toolCode != null) return targetSelectionObservation(operation, kind, "SKILL目标需要skillCode，不能同时提供toolCode；请按实际查询目标重新提交，不要改换已选模型。");
            try {
                JSONObject selected = object(verified.requestJson()).getJSONObject("_selectedSkill");
                boolean pinned = selected != null && skillCode.equals(selected.getString("code"));
                if (pinned && (StrUtil.isBlank(selected.getString("versionId")) || StrUtil.isBlank(selected.getString("packageDigest"))))
                    return failed("MODEL_CATALOG_TARGET_INVALID", "本轮Skill固定版本信息不完整");
                var resolved = skillModelScopes.resolve(verified.binding().getAgentVersionId(),
                        invocation.getUserId(), verified.moduleCode(), skillCode,
                        pinned ? selected.getString("versionId") : null, pinned ? selected.getString("packageDigest") : null, verified.transport());
                var configuration = resolved.configuration(); requiredCapability = resolved.capabilityCode();
                codes = configuration.selectableModelCodes(); defaultCode = configuration.defaultModelCode();
            } catch (ServiceException unavailable) {
                return failed("MODEL_CATALOG_TARGET_UNAVAILABLE", unavailable.getMessage());
            }
        } else {
            if (skillCode != null || "AGENT".equals(kind) && toolCode != null)
                return targetSelectionObservation(operation, kind, "当前targetKind与skillCode或toolCode冲突：AGENT不带两者，TOOL只带toolCode，SKILL只带skillCode。按当前目标纠正查询参数，不需要重新列出候选模型。");
            String expectedType;
            if ("AGENT".equals(kind)) {
                codes = scope.agentTextModelCodes().stream().sorted().toList(); expectedType = "text";
                defaultCode = object(verified.binding().getModelConfigJson()).getString("defaultModelCode");
            } else {
                if (toolCode == null || !scope.generationTypes().containsKey(toolCode)) {
                    JSONArray targets = new JSONArray();
                    String targetModelType = modelType;
                    scope.generationTypes().entrySet().stream().sorted(Map.Entry.comparingByKey())
                            .filter(entry -> targetModelType == null || targetModelType.equals(entry.getValue()))
                            .forEach(entry -> {
                                JSONObject target = new JSONObject();
                                target.put("toolCode", entry.getKey());
                                target.put("modelType", entry.getValue());
                                targets.add(target);
                            });
                    if (targets.isEmpty())
                        return failed("MODEL_CATALOG_TARGET_UNAVAILABLE", "当前智能体未绑定所请求类型的媒体生成工具");
                    JSONObject detail = new JSONObject();
                    detail.put("lookupStatus", "NEEDS_TARGET");
                    detail.put("reasonCode", "MODEL_CATALOG_TARGET_UNAVAILABLE");
                    detail.put("targetKind", "TOOL");
                    detail.put("allowedTargets", targets);
                    detail.put("message", "请求的toolCode不可用；从allowedTargets逐字选择与本次需求一致的真实工具编码后重新查询。未读取模型列表、未替换工具、未执行生成。不能确定用途时先询问用户。");
                    return succeeded(envelope(operation, new JSONArray(), 0, 1, DEFAULT_PAGE_SIZE,
                            false, detail), "需要确认真实媒体工具编码");
                }
                codes = null; expectedType = scope.generationTypes().get(toolCode);
            }
            if (modelType != null && !expectedType.equals(modelType)) return failed("MODEL_CATALOG_FILTER_INVALID", "模型类型与查询目标不一致");
            modelType = expectedType;
        }
        if (modelType != null && !scope.modelTypes().contains(modelType)) return failed("MODEL_CATALOG_FILTER_INVALID", "当前目标未开放此类模型");
        if ("DETAIL".equals(operation)) {
            List<String> missing = List.of("modelCode", "providerCode").stream()
                    .filter(key -> StrUtil.isBlank(arguments.getString(key))).toList();
            if (!missing.isEmpty()) {
                RuntimeToolResult clarification = lookupObservation(arguments, "NEEDS_IDENTIFIERS",
                        "MODEL_CATALOG_DETAIL_IDENTIFIERS_REQUIRED",
                        "模型详情查询缺少" + String.join("、", missing) + "；先按nextQuery读取当前目标候选，再复制真实标识，不猜测参数。");
                JSONObject output = JSON.parseObject(clarification.getOutputJson());
                JSONObject detail = output.getJSONObject("detail");
                detail.put("missingFields", missing);
                for (JSONObject target : List.of(detail, detail.getJSONObject("nextQuery"))) {
                    target.put("targetKind", kind);
                    if (skillCode != null) target.put("skillCode", skillCode);
                    if (toolCode != null) target.put("toolCode", toolCode);
                }
                clarification.setOutputJson(output.toJSONString());
                return clarification;
            }
        }
        AiModelListRequest request = new AiModelListRequest(); request.setModelType(modelType);
        request.setGenerateMode(trimToNull(arguments.getString("generateMode")));
        List<AiModelVO> available;
        if ("DETAIL".equals(operation)) {
            String exact = trimToNull(arguments.getString("modelCode"));
            available = exact == null || codes != null && !codes.contains(exact) ? List.of()
                    : modelBusinessService.listAvailableModelsByCodes(request, List.of(exact));
        } else available = codes == null ? modelBusinessService.listAvailableModels(request)
                : modelBusinessService.listAvailableModelsByCodes(request, codes);
        if (available == null) available = List.of();
        final List<String> authorizedCodes = codes;
        final String requestedType = modelType;
        final String targetCapability = requiredCapability;
        final String selectedTool = "TOOL".equals(kind) ? toolCode : null;
        available = available.stream().filter(Objects::nonNull)
                .map(model -> selectedTool == null ? model : toolRegistry.projectModelCatalog(selectedTool, model))
                .filter(Objects::nonNull)
                .map(model -> projectCapability(model, targetCapability)).filter(Objects::nonNull)
                .filter(model -> scope.modelTypes().contains(model.getModelType()))
                .filter(model -> requestedType == null || requestedType.equals(model.getModelType()))
                .filter(model -> authorizedCodes == null || authorizedCodes.contains(model.getModelCode()))
                .filter(model -> StrUtil.isNotBlank(model.getModelCode()) && StrUtil.isNotBlank(model.getProviderCode())
                        && StrUtil.isNotBlank(model.getProviderName()) && model.getBilling() != null && !capabilityCodes(model).isEmpty()).toList();
        if (available.stream().anyMatch(model -> capabilityCodes(model).size() > MAX_CAPABILITY_CODES))
            return failed("MODEL_CATALOG_CAPABILITIES_TOO_LARGE", "模型能力目录超过安全返回范围");
        RuntimeToolResult result = "DETAIL".equals(operation) ? detail(arguments, available) : list(arguments, available);
        if (!"SUCCEEDED".equals(result.getStatus())) return result;
        JSONObject output = JSON.parseObject(result.getOutputJson());
        for (Object item : output.getJSONArray("items")) if ("SKILL".equals(kind)) ((JSONObject)item).put("selectionScope", "SKILL_FIXED_CANDIDATE");
        JSONObject detail = output.getJSONObject("detail");
        if (detail == null) { detail = new JSONObject(); output.put("detail", detail); }
        if(selectedTool!=null && "DETAIL".equals(operation) && "AVAILABLE".equals(detail.getString("lookupStatus"))
                && available.size()==1) {
            var inputSupport=toolRegistry.modelCatalogInputSupport(selectedTool,available.get(0));
            if(!inputSupport.isEmpty()) {
                var scopedSupport=new java.util.LinkedHashMap<String,Object>(inputSupport);
                scopedSupport.put("modelCode",available.get(0).getModelCode());
                scopedSupport.put("providerCode",available.get(0).getProviderCode());
                detail.put("toolInputSupport",scopedSupport);
                detail.put("toolInputSupportScope", targetCapability != null
                        || trimToNull(request.getGenerateMode()) != null ? "CAPABILITIES" : "MODEL");
            }
        }
        detail.put("targetKind", kind); if (skillCode != null) detail.put("skillCode", skillCode);
        if (toolCode != null) detail.put("toolCode", toolCode); if (defaultCode != null) detail.put("defaultModelCode", defaultCode);
        detail.put("defaultModelStatus", StrUtil.isNotBlank(defaultCode) ? "CONFIGURED" : "NOT_CONFIGURED");
        if ("TOOL".equals(kind) && StrUtil.isBlank(defaultCode))
            detail.put("selectionNote", "当前媒体工具没有声明默认模型；列表顺序、首项及唯一候选均不是默认配置。用户仅要求使用默认模型时，应说明未配置并请求选择；不得把目录首项称为默认模型或据此生成。已有明确模型选择可按定向详情继续校验。");
        if (requiredCapability != null) detail.put("capabilityCode", requiredCapability);
        if ("AVAILABLE".equals(detail.getString("lookupStatus")) && "SKILL".equals(kind)) {
            detail.put("selectionScope", "SKILL_FIXED_CANDIDATE"); detail.put("selectionMutable", true);
        }
        JSONObject next = detail.getJSONObject("nextQuery");
        if (next != null) { next.put("targetKind", kind); if (skillCode != null) next.put("skillCode", skillCode); if (toolCode != null) next.put("toolCode", toolCode); }
        if (detail.toJSONString().getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_UTF8_BYTES)
            return failed("MODEL_CATALOG_DETAIL_TOO_LARGE", "所选模型详情超过安全返回范围");
        result.setOutputJson(output.toJSONString()); return result;
    }

    private RuntimeToolResult targetSelectionObservation(String operation, String kind, String message) {
        JSONObject detail = new JSONObject();
        detail.put("lookupStatus", "NEEDS_TARGET");
        detail.put("reasonCode", "MODEL_CATALOG_TARGET_CONFLICT");
        detail.put("targetKind", kind);
        detail.put("message", message + " 本次未读取模型、未替换目标、未执行生成。");
        return succeeded(envelope(operation, new JSONArray(), 0, 1, DEFAULT_PAGE_SIZE, false, detail),
                "模型查询目标参数需要澄清");
    }

    private AiModelVO projectCapability(AiModelVO model, String requiredCapability) {
        if (requiredCapability == null) return model;
        List<ModelCapabilityOptionVO> options = model.getAvailableCapabilities() == null ? List.of()
                : model.getAvailableCapabilities().stream().filter(Objects::nonNull)
                        .filter(option -> requiredCapability.equals(option.getCode())).toList();
        if (options.size() != 1) return null;
        ModelCapabilityOptionVO option = options.get(0);
        AiModelVO projected = new AiModelVO();
        org.springframework.beans.BeanUtils.copyProperties(model, projected);
        projected.setCapabilityCode(option.getCode()); projected.setGenerateMode(option.getGenerateMode());
        projected.setParameterSchema(option.getParameterSchema()); projected.setParameterRules(option.getParameterRules());
        projected.setBilling(option.getBilling()); projected.setAvailableCapabilities(List.of(option));
        return projected;
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean contains(List<String> values, String keyword) {
        return values != null && values.stream().filter(Objects::nonNull)
                .anyMatch(value -> contains(value, keyword));
    }

    private String safeText(String value, String fallback) {
        return StrUtil.isBlank(value) ? fallback : value.trim();
    }

    private String trimToNull(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private JSONObject object(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_PARAMETERS_INVALID);
        }
    }

    private RuntimeToolResult succeeded(JSONObject output, String summary) {
        RuntimeToolResult result = RuntimeToolResult.succeeded(
                output.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        result.setSafeSummary(summary);
        return result;
    }

    private RuntimeToolResult failed(String errorCode, String summary) {
        RuntimeToolResult result = new RuntimeToolResult();
        result.setStatus("FAILED"); result.setErrorCode(errorCode); result.setSafeSummary(summary);
        result.setOutputJson("{}"); result.setRetryable(false);
        return result;
    }
}
