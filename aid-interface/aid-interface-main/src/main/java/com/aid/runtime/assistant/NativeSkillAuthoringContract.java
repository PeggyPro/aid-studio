package com.aid.runtime.assistant;

import java.util.Map;

/** Single Native authoring contract for planner guidance and authoritative resource verification. */
public final class NativeSkillAuthoringContract
{
    public static final String SKILL_CODE = "skill_authoring_flow";
    public static final String MASTER_PATH = "SKILL.md";
    public static final String READ_RESOURCE = "aid.skill.read-resource";
    public static final String PROPOSE_DRAFT = "aid.skill.propose-draft";
    public static final String PROPOSE_DRAFT_V2 = "aid.skill.propose-draft-v2";

    public static boolean isProposalTool(String code) {
        return PROPOSE_DRAFT.equals(code) || PROPOSE_DRAFT_V2.equals(code);
    }

    public static java.util.Set<String> proposalToolCodes() {
        return java.util.Set.of(PROPOSE_DRAFT, PROPOSE_DRAFT_V2);
    }
    public static final Map<String, String> SOURCE_BRANCHES = Map.of(
            "SCRATCH", "references/idea_workshop.md",
            "CONVERSATION", "references/dialogue_recipe.md",
            "CANVAS", "references/canvas_recipe.md");

    private NativeSkillAuthoringContract() { }

    public static String storageInstruction()
    {
        return "原生计划包含" + PROPOSE_DRAFT + "时，须在当前批准计划显式安排至少两个独立的TOOL读取步骤，"
                + "使用" + READ_RESOURCE + "按顺序读取同一固定版本的" + SKILL_CODE + "主文件"
                + MASTER_PATH + "与当前sourceMode对应的来源分支。分支读取步骤必须通过dependsOn依赖主文件读取；"
                + "所有非只读步骤通过dependsOn直接或传递依赖这两份当前计划读取。草稿保存器核对完整依赖闭包，"
                + "同时验证实际资源路径、固定版本、摘要、原审批和成功读取回执；两个任意读取不能代替主文件与分支。"
                + "只有想法且无已验证创作记录时使用SCRATCH→" + SOURCE_BRANCHES.get("SCRATCH")
                + "；已有可验证创作对话使用CONVERSATION→" + SOURCE_BRANCHES.get("CONVERSATION")
                + "；已有画布证据使用CANVAS→" + SOURCE_BRANCHES.get("CANVAS")
                + "。不能仅因本轮刚完成想法讨论就把SCRATCH改为CONVERSATION。"
                + "历史探索或旧计划的读取不能替代当前计划前置条件；保存只创建PRIVATE草稿，不发布或启用。"
                + "当前批准计划的前置读取尚未完成时，只暴露读取函数是正常阶段，不代表保存工具缺失；"
                + "先执行本轮可用的当前计划读取步骤，前置条件完成后才会暴露保存函数，不能用重复说明代替读取。"
                + "仅保存私人草稿的完成判据使用TARGET_WRITE，成功保存步骤的runtime-step回执列入"
                + "targetWriteEvidenceRefs；Java会复核当前用户仍持有的未发布私人草稿及其原保存摘要。"
                + "草稿保存输出DURABLE_RECEIPT，后续从priorResults.observation读取已核验私人草稿回执；"
                + "它不是可定位画布目标，不得编造节点、目标引用或RuntimeArtifact，"
                + "也不可用来宣称媒体归档、创作质量或真实测试通过。"
                + "definition.outputKind必填TEXT/IMAGE；IMAGE另填capabilityCode，"
                + "图片Schema由平台填入。"
                + "TEXT表示文字类能力，不限定业务结果必须是纯文本。普通报告的章节、排版和写作要求写入"
                + "SKILL.md与systemPrompt。正文outputSchema使用description和字符串examples，省略根type及properties；"
                + "只有下游确实需要逐字段读取时才声明结构化outputSchema，并设置definition.methodOutputMode为JSON。"
                + "methodOutputMode属于definition，不能作为outputSchema或inputSchema的关键字。"
                + "JSON方法的definition示例为{\"outputKind\":\"TEXT\",\"methodOutputMode\":\"JSON\"}；"
                + "outputSchema只包含业务JSON契约及examples。"
                + "结构化结果必须完整满足声明，不能同时填写definition.methodOutputMode:TEXT或definition.structuredOutputEnabled:false。"
                + "outputSchema约束业务result，不包含运行状态、任务编号或扣费信封；不支持的Schema关键字须按校验错误修正。"
                + "TEXT方法的inputSchema使用x-aid-input-mode标记业务输入：NONE不声明业务字段或输入样例；"
                + "FREE_FORM接收用户正文或附件，只填写模式、描述及样例，省略根type及properties，样例用正文或含text/attachments的对象；"
                + "STRUCTURED声明实际输入字段、约束和匹配的对象样例。"
                + "输入有业务数据时填写inputSchema.examples；文本或JSON交付必须填写outputSchema.examples。"
                + "examples为数组，文本样例用字符串，结构化样例用与对应契约匹配的对象；示例值不可充当默认参数。"
                + "保存校验失败须修正。";
    }

    public static String plannerInstruction() {
        return "当前目录提供新版保存工具" + PROPOSE_DRAFT_V2 + "时优先使用新版；两种保存工具均须遵循相同来源权限和当前计划依赖。" + storageInstruction()
                + "生成的Skill必须使用通用执行契约，不能要求给新名称添加白名单或修改代码。"
                + "先区分方法输出与最终作品：写作、分析、多步骤制作及需要编排画布工具的方法使用TEXT；"
                + "即使最终目标包含图片或视频，方法本身仍返回文字类产物，由主助手调用当前授权目录内的工具完成落地。"
                + "方法正文必须说明输入、事实保留约束、输出结构、真实工具依赖和失败恢复，不编造工具名称、权限或完成回执。"
                + "生成的systemPrompt应要求运行时区分用户已确认事实、合理补全和待确认建议；"
                + "对时间、数量、人物身份、位置、因果及否定约束逐项核对，不得为润色省略限定词或改变含义，"
                + "也不得把本次示例的姓名、地点、数值写死成以后任务的固定条件。"
                + "只有直接通过图片模型执行的提示词包才声明IMAGE，能力仅能选择text_to_image或image_to_image；"
                + "所需模型必须来自真实模型目录并支持该能力。图片输入与结果必须遵守平台共享图片契约，"
                + "不能把图片结果写成普通文字或在提示词里自行扣费。"
                + "平台无法提供的能力应明确列为不支持，不得将仅有提示词的草稿描述为已实现该能力。"
                + "保存工具的definition必须明确填写outputKind：正文方法填{\"outputKind\":\"TEXT\"}，"
                + "JSON方法填{\"outputKind\":\"TEXT\",\"methodOutputMode\":\"JSON\"}；"
                + "直接图片包填{\"outputKind\":\"IMAGE\",\"capabilityCode\":\"text_to_image\"}或image_to_image。"
                + "图片包inputSchema和outputSchema可传空对象，由保存工具填入平台固定契约，禁止自行设计图片任务格式。"
                + "保存前平台会执行完整包校验；返回字段错误时先修正，不能把拒绝当作保存成功。"
                + "直接图片包使用共享inputSchema="
                + com.aid.skill.service.SkillImageExecutionContract.INPUT_SCHEMA
                + "；共享outputSchema=" + com.aid.skill.service.SkillImageExecutionContract.OUTPUT_SCHEMA;
    }
}
