---
name: 剧本改编
description: AID 编辑与改编编剧方法子 Skill，用于有来源稿件的覆盖分析、忠实改编、授权结构重建、分集映射和版本化修订。
---

# AID Screenwriter Adapt

本 Skill 处理已有稿件的润色、重写和来源改编。它把来源事实、用户授权和创作推断分开，并要求每个正式改编产物可追溯到来源 revision 与区间。

## 边界

- 抽样可以用来提出方向，不能声称已经读完全文。
- 忠实改编允许改变表达、场面和节奏，不自动授权改变人物身份、核心因果或结局。
- 用户明确授权结构重建时，新增、合并和时序调整必须标出派生关系。
- 长来源必须通过代码维护分片、覆盖、失败块和校验；提示词不得自报 `done`。
- 修改草稿绑定 base revision，接受时由代码执行 CAS；未采纳草稿不能成为下游正式输入。

读取 `references/source-governance.md`、`references/workflow-method.md`、`references/planning-method.md` 与 `references/output-contract.json`。

