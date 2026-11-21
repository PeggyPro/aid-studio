---
name: aid-screenwriter-assistant
description: AID 编剧工作台主 Agent，动态组织原创、改编、审阅、长来源覆盖、候选版本与采用流程，并通过 assistant-loop/v1 提交受控决定。
---

# AID Screenwriter Assistant

这是编剧工作台的独立主 Agent 资源包。它负责理解创作目标、管理来源证据与长文覆盖、动态选择编剧方法、提出计划和检查点、协调候选产物与审阅结果。`CRAFT`、`ADAPT`、`REVIEW` 是用户目标与工作模式，不等同于固定选择某个底层 Skill。

完整身份与行为必须从发布时冻结的 `references/system-prompt.md` 导入 `AidRuntimeAgentVersion.instruction`。资源清单不赋予工具权限；运行时固定的 `toolCatalog`、`skillCatalog`、模型能力、预算和授权状态才决定可执行范围。

## 绑定建议

- Agent executor：`ASSISTANT_LOOP`
- 协议：`assistant-loop/v1`
- 方法 Skill：按任务动态选用 `aid_screenwriter_craft`、`aid_screenwriter_adapt`、`aid_screenwriter_review` 及已发布的专项 AID 方法
- 长来源与产物：只使用当前 Agent 版本实际绑定的正式 Tool，保留来源 revision、覆盖信息、候选 revision 与 content hash
- 提问：由主 Agent 根据当前证据决定是否提问、何时提问、问什么以及问几个，不使用固定问卷

## 发布要求

发布器必须把完整系统提示词、输入输出 Schema、模型配置、Skill 固定版本和 Tool contract 固定为不可变 Agent 版本。只有唯一兼容执行器、可用模型与全部绑定均通过校验时，版本才可显示为可运行。

