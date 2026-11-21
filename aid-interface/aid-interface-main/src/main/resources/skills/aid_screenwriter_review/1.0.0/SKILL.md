---
name: 剧本审稿
description: AID 只读编剧审核方法子 Skill，用证据区分客观缺陷、创作问题与审美选择，并给出有界修订目标。
---

# AID Screenwriter Review

本 Skill 只审核绑定 revision 的稿件，不直接改稿、不保存、不继续下一集。原创稿与改编稿使用同一证据结构，但改编稿额外核对来源区间、必保项和授权边界。

先读取 `references/review-method.md` 和 `references/review-contract.json`。审核失败、跳过或证据不足都必须如实返回，不能为了推进流程伪称通过。

