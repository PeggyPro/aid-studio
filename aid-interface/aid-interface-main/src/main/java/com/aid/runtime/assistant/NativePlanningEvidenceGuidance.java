package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Provider guidance derived from the authorized catalog, never an execution permission. */
final class NativePlanningEvidenceGuidance {
    private NativePlanningEvidenceGuidance() { }

    static void project(JSONObject request) {
        JSONObject evidence = request == null ? null : request.getJSONObject("completionEvidenceContract");
        JSONArray catalog = request == null ? null : request.getJSONArray("toolCatalog");
        if (evidence == null || catalog == null) return;
        Set<String> targets = new LinkedHashSet<>();
        JSONArray targetCodes = evidence.getJSONArray("targetWriteToolCodes");
        if (targetCodes != null) {
            for (Object code : targetCodes) if (code instanceof String text) targets.add(text);
        }
        JSONArray routes = new JSONArray();
        boolean artifactPossible = false;
        boolean targetPossible = false;
        for (Object raw : catalog) {
            if (!(raw instanceof JSONObject tool)) continue;
            String code = tool.getString("toolCode");
            if (code == null || code.isBlank()) continue;
            String kind = tool.getString("outputReferenceKind");
            JSONArray criteria = new JSONArray();
            if ("RUNTIME_ARTIFACT".equals(kind)) {
                criteria.add("TECHNICAL_ARCHIVE");
                artifactPossible = true;
            }
            if (targets.contains(code)) {
                criteria.add("TARGET_WRITE");
                targetPossible = true;
            }
            JSONObject route = new JSONObject();
            route.put("toolCode", code);
            route.put("outputReferenceKind", kind);
            route.put("persistenceCriterionKinds", criteria);
            routes.add(route);
        }
        evidence.put("planningEvidenceByTool", routes);
        // The generic text-node example is misleading when this Agent has no such verifier.
        if (!targetPossible) evidence.remove("textOnlyPlanExample");
        JSONArray examples = new JSONArray();
        if (artifactPossible) examples.add(example("TECHNICAL_ARCHIVE",
                "Requested Runtime artifacts are finalized by the listed producer steps; completion cites their actual artifact:* receipts."));
        if (targetPossible) examples.add(example("TARGET_WRITE",
                "Requested supported target writes succeed; completion cites their actual current-plan runtime-step:* receipts."));
        evidence.put("applicablePersistenceExamples", examples);
        evidence.put("persistenceRouting", instruction());
        evidence.put("readOnlyRouting", readOnlyInstruction());
        evidence.put("artifactOutputContract", "所有声明outputReferenceKind=RUNTIME_ARTIFACT的TOOL步骤，不论是否收费，outputLogicalArtifactIds均必须且只能声明一个1至64字符的稳定结果标识。首字符为字母或数字，其余仅字母、数字、点、下划线、冒号或连字符。构建、生成、归档预览不能留空；程序不会代填。只有真实执行及归档后才有产物回执。");
        evidence.put("billableOutputContract", "每个计费TOOL步骤的outputLogicalArtifactIds必须声明且仅声明一个稳定结果标识（1至64字符，首字符为字母或数字，其余仅字母、数字、点、下划线、冒号或连字符）。这是生成结果的逻辑标识，不是真实产物回执；执行成功后才取得真实回执。生成图片、视频等步骤不能留空，程序不会代填。不要为只读步骤添加虚假产物。");
        evidence.put("reviewCriterionKinds", List.of("CONTENT_REQUIREMENT", "CREATIVE_QUALITY"));
        evidence.put("reviewCriterionPrerequisite",
                "These require the actual Runtime artifact AND its current PASS review. Persistence alone does not prove quality. "
                        + "Do not add a quality criterion to an intermediate save stage that has no review step.");
    }

    static String instruction() {
        return "TARGET_WRITE is not a generic name for every write. Use it only for tool codes in "
                + "completionEvidenceContract.targetWriteToolCodes that are also listed in the current plan. "
                + "A RUNTIME_ARTIFACT producer uses TECHNICAL_ARCHIVE for persistence, even if its operation is called write, save or checkpoint. "
                + "The planningEvidenceByTool table describes possible evidence, not success: complete only from actual successful receipts. "
                + "OBSERVATION and unverified UNKNOWN outputs do not by themselves prove persistence. "
                + "An empty target-write list means no TARGET_WRITE criterion is supported by this catalog. "
                + "Do not change criterion kinds, invent receipts or add unwanted operations after execution to claim success.";
    }

    static String readOnlyInstruction() {
        return "信息查询与执行计划是不同路径。单纯查询直接调用当前已授权的只读工具；"
                + "读取回执要求补查标识或给出nextQuery时，继续所需读取，不为补查新建执行计划。"
                + "信息齐全后使用assistant_answer如实回答，未提供的信息明确说明；"
                + "只有用户要求执行Skill、生成产物或写入等操作时，才提交能形成真实完成证据的执行计划。"
                + "METHOD_RESULT仅证明实际成功的SKILL步骤，不适用于TOOL只读结果；"
                + "不得为满足判据额外生成、保存或调用无关Skill。已有执行计划仍按原计划和真实证据完成。";
    }

    private static JSONObject example(String kind, String description) {
        JSONObject value = new JSONObject();
        value.put("criterionKind", kind);
        value.put("description", description);
        return value;
    }
}
