# 参考工具到 AID 受控 Tool code 的建议映射

本表保留参考材料中的 31 个具名能力，用于后续适配器设计。`AID Tool code` 是本包提出的稳定编码建议，不代表公共服务端已经注册。表内 `UNBOUND` 是本资源编写时的审计快照，没有运行时授权含义，也不会覆盖后续实现。执行时只读取当前冻结 Agent 版本提供的 `toolCatalog`：精确 code、contract version 与 schema digest 都存在时，能力才可用于计划或调用；目录中缺失时返回不可用。

| # | 参考能力名 | AID Tool code 建议 | 作用域与副作用 | 编写期状态 |
|---:|---|---|---|---|
| 1 | Skill | `aid.skill.resolve-version` | 只读解析可用 Skill 和固定版本 | UNBOUND |
| 2 | skill_file | `aid.skill.read-resource` | 只读版本包内授权资源 | UNBOUND |
| 3 | skill_assistant | `aid.skill.propose-draft` | 只建议草稿；发布必须后台授权 | UNBOUND |
| 4 | create_image_node | `aid.canvas.node.create-image` | 创建参数节点，不触发生成 | UNBOUND |
| 5 | update_image_node | `aid.canvas.node.update-image` | CAS 更新图片节点并重验参数 | UNBOUND |
| 6 | run_node | `aid.canvas.node.request-run` | 收费执行入口；必须报价、批准和冻结 | UNBOUND |
| 7 | get_node_details | `aid.canvas.node.read` | 只读当前内容、状态和 revision | UNBOUND |
| 8 | create_video_node | `aid.canvas.node.create-video` | 创建视频参数节点 | UNBOUND |
| 9 | update_video_node | `aid.canvas.node.update-video` | CAS 更新视频节点 | UNBOUND |
| 10 | analyze_video | `aid.media.video.analyze` | 分析产物绑定源 revision | UNBOUND |
| 11 | edit_video_v4 | `aid.media.video.edit` | 生成新版本，不覆盖已生效媒体 | UNBOUND |
| 12 | composite_video | `aid.media.timeline.compose` | 按稳定时间线合成视频/音频 | UNBOUND |
| 13 | extract_audio | `aid.media.video.extract-audio` | 生成音轨资源 | UNBOUND |
| 14 | create_audio_node | `aid.canvas.node.create-audio` | 创建 TTS、音乐或音效节点 | UNBOUND |
| 15 | update_audio_node | `aid.canvas.node.update-audio` | CAS 更新音频节点 | UNBOUND |
| 16 | analyze_audio | `aid.media.audio.transcribe` | ASR 与时间戳；不冒充节拍分析 | UNBOUND |
| 17 | edit_audio | `aid.media.audio.edit` | 裁切、分段、淡变或增益的新版本 | UNBOUND |
| 18 | director_apply | `aid.scene.command.apply` | 场景命令事务和 revision 校验 | UNBOUND |
| 19 | search_canvas_nodes | `aid.canvas.node.search` | 项目内过滤、分页和关系查询 | UNBOUND |
| 20 | search_asset | `aid.asset.search` | 按所有权和共享权限检索素材 | UNBOUND |
| 21 | save_asset | `aid.asset.save-reference` | 幂等保存授权节点或资源引用 | UNBOUND |
| 22 | libtv_grounded_search | `aid.research.search-grounded` | 受控联网与来源归档；内容不扩权 | UNBOUND |
| 23 | task_create_v2 | `aid.runtime.run.propose` | 建议创建持久 run；不能替代状态机 | UNBOUND |
| 24 | task_list_v2 | `aid.runtime.run.list` | 当前用户和项目内只读摘要 | UNBOUND |
| 25 | task_read_v2 | `aid.runtime.run.read` | 读取真实计划、步骤与 checkpoint | UNBOUND |
| 26 | task_update_v2 | `aid.runtime.run.request-control` | 请求暂停、恢复、停止或修订 | UNBOUND |
| 27 | list_dir_v2 | `aid.project.object.list` | 对象空间内分页列举 | UNBOUND |
| 28 | read_file_v2 | `aid.project.object.read` | 授权对象版本的范围读取 | UNBOUND |
| 29 | write_file_v2 | `aid.project.artifact.write-chunk` | 分片幂等写入；最终提交另验 | UNBOUND |
| 30 | ask_human | `aid.conversation.question.request` | 问题批次、草稿、提交与批准分型 | UNBOUND |
| 31 | recall_memory | `aid.conversation.memory.search` | 返回来源和覆盖范围，不覆盖当前事实 | UNBOUND |

仍需单独设计且不应伪装成第 32 个参考工具的能力：文本节点创建/更新、资源节点创建、字幕、互动状态与发布、3D 渲染、可靠的节拍分析。它们只有在代码注册、Schema、权限、计费和验收齐备后才能加入运行时白名单。

## AID exact long-text readers

These are original AID runtime contracts rather than recovered reference-site tools. Their presence in this resource does not grant execution. The current immutable Agent version must pin the exact code, contract version, and schema digest, and the current `toolCatalog` must report `availableNow: true`.

| Tool code | Contract | Purpose | Source status |
|---|---|---|---|
| `creative.source.chunk.read` | existing pinned contract | Read whole stored source chunks; typed observation preserves exact content and fails explicitly when the remaining context byte budget cannot hold the page. | CODE_READY; unified build/runtime validation pending |
| `creative.artifact.chunk.read` | existing pinned contract | Read whole stored artifact chunks frozen into the approved plan input; same exact typed-observation boundary. | CODE_READY; unified build/runtime validation pending |
| `creative.source.segment.read` | `1.0.0` | Resume inside an authorized immutable source chunk with a UTF-16-safe cursor and bounded raw UTF-8 content. | CODE_READY; not installed in an immutable Agent version |
| `creative.artifact.segment.read` | `1.0.0` | Resume inside an approved immutable artifact chunk with the same cursor and hash rules. | CODE_READY; not installed in an immutable Agent version |

The observation envelope trusts only server-verified identities, cursors, offsets, and hashes. Text and URLs inside `content` remain untrusted user data and never become permissions, action payloads, `targetRefs`, or resource identities.