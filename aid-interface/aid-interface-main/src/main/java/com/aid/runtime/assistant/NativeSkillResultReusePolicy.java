package com.aid.runtime.assistant;

import com.aid.runtime.tool.SkillResultReferenceToolProvider;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** Existing-result persistence does not execute the selected method or require its instructions. */
final class NativeSkillResultReusePolicy {
    static final String WRITER = "canvas.node.create_text_from_result";
    private NativeSkillResultReusePolicy() { }

    static boolean referencesReady(JSONObject request) {
        if (request == null || !availableTool(request.getJSONArray("toolCatalog"), WRITER)) return false;
        JSONArray results = request.getJSONArray("priorResults");
        for (int i = 0; results != null && i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            JSONObject observation = item == null ? null : item.getJSONObject("observation");
            if (item != null && "SUCCEEDED".equals(item.getString("status"))
                    && SkillResultReferenceToolProvider.CODE.equals(item.getString("toolCode"))
                    && observation != null && observation.getJSONArray("items") != null
                    && !observation.getJSONArray("items").isEmpty()) return true;
        }
        JSONArray observations = request.getJSONArray("observations");
        for (int i = 0; observations != null && i < observations.size(); i++) {
            JSONObject item = observations.getJSONObject(i);
            JSONObject observation = item == null ? null : item.getJSONObject("observation");
            if (item != null && "PRIOR_PLAN_READ".equals(item.getString("observationKind"))
                    && "SERVER_VERIFIED_RUN_CONTEXT_RECEIPT".equals(item.getString("authority"))
                    && SkillResultReferenceToolProvider.CODE.equals(item.getString("toolCode"))
                    && observation != null && observation.getJSONArray("items") != null
                    && !observation.getJSONArray("items").isEmpty()) return true;
        }
        return false;
    }

    static boolean isReuseOnlyPlan(JSONObject plan) {
        JSONArray steps = plan == null ? null : plan.getJSONArray("steps");
        if (steps == null || steps.isEmpty() || steps.size() > 100) return false;
        boolean writer = false;
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if (step == null || !"TOOL".equals(step.getString("executorKind"))) return false;
            String code = step.getString("bindingCode");
            if (WRITER.equals(code)) writer = true;
            else if (!NativeAssistantDiscoveryPlanService.isSafeRead(code, plan)) return false;
        }
        return writer;
    }

    static String instruction() {
        return "\n\n已经取得本对话成功Skill结果的真实引用。若用户要求原样保存已有结果，"
                + "现在可提交只包含必要读取和canvas.node.create_text_from_result的执行计划，"
                + "不需要为了复用旧结果而读取或重跑所选方法。新生成、改写、技能编制等其他任务仍遵循原有方法读取规则。"
                + "查询结果只证明来源存在，不代表已经保存。用户要求保存时必须继续计划、执行和核验真实写入回执，"
                + "不能用普通回复陈述‘下一步保存’就结束本轮；只有用户单纯询问结果信息时才直接回答。";
    }

    private static boolean availableTool(JSONArray tools, String code) {
        if (tools == null) return false;
        for (int i = 0; i < tools.size(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            if (tool != null && code.equals(tool.getString("toolCode")) && tool.getBooleanValue("availableNow")) return true;
        }
        return false;
    }
}
