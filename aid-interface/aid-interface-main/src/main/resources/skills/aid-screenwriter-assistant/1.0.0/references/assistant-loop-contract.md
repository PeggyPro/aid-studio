# assistant-loop/v1 资源侧约束

本文件用于发布审计，运行时冻结 Agent 版本的 `outputSchemaJson` 与唯一 `AssistantLoopExecutor` 是校验权威。

- action 仅允许 `CHAT`、`ASK`、`PLAN`、`TOOL`、`SKILL`、`CHECKPOINT`、`COMPLETE`、`FAIL`。
- 八个载荷必须全部出现，且只有 action 对应的小写载荷非空。
- `observedRunRevision` 与 `observedPlanRevision` 必须是大于等于零的整数。
- `ASK` 的问题由主 Agent 按当前证据动态生成；`TEXT` 问题的选择上下限使用运行时 schema 规定的零值，选择题遵守成员和数量校验。
- `PLAN` 的 `parentRevision` 是大于等于零的整数，步骤只能引用固定目录中存在的 Tool 或 Skill。
- `SKILL.skillVersionId` 是正整数，package digest 必须与固定版本一致。
- Tool、Skill、计划、产物和完成证据由运行时复核；模型不能提供授权、计费、付款人、幂等键、lease 或 fence。


Wire constraints mirror the runtime-owned output schema: `message.content` is limited to 100,000 characters; `SKILL.skillVersionId` is a positive decimal string of at most 19 digits; `TEXT` questions use `null` for both selection bounds and no options, while `SINGLE` and `MULTI` use valid non-null integer bounds and non-empty options.
`ASK` wire rules also require `allowFreeText`: it is `true` for `TEXT`; for `SINGLE` and `MULTI`, free-text supplements are accepted only when this boolean is `true`.

PLAN steps require `maxAttempts` from 1 through 10. PLAN completion criteria use stable `criterionId`, typed `criterionKind`, and a description; COMPLETE must cover that exact set with typed evidence. Technical media archive receipts prove storage completion only, while creative quality and content requirements require PASS review receipts.
