# Agent 流式协议编辑

新草稿采用 `assistant-loop/native-v1` 与 `responsePolicy.mode=NATIVE_TOOL_STREAM`。页面保留服务端返回的实际协议；历史草稿不会因打开编辑器被静默切换。显式切换协议同时切换响应策略，必须重新保存和校验。

自然语言正文逐段返回，工具调用有独立的名称与参数事件。输入输出 Schema 校验运行接口，不要求聊天正文是 JSON。模型的流式响应、原生工具及其流式传输能力由服务端实际目录与适配共同校验。页面不能自行声明模型支持这些能力，也不新增自动修复调用。

## 草稿保存与发布

使用既有 `/aid/runtime-agent/draft/detail`、`draft/save`、`draft/validate` 和 `draft/publish`。请求保留 `X-Aid-Runtime-Agent-Family: ASSISTANT_LOOP`，不调用旧计划执行器。

- 表单正文 `instruction` 提交时同步为 `mainMarkdown` 与 `systemPrompt`，三者是一份 SP 正文。
- `resources` 完整保留服务端当前草稿返回的受控资源；未返回完整包时拒绝保存，避免覆盖未呈现内容。
- `packageVersionCode` 可空。服务端固定该值时，发布版本自动填写并禁止修改；未固定时管理员填写 SemVer。正文不嵌入版本信息。
- `baseVersionId`、草稿 ID、摘要及所有 64 位标识原样传递。版本发布仍需服务端检查权限、摘要、绑定与运行能力。
- 修改协议或正文后先保存；有未保存修改或操作占用时不能发布旧草稿。操作中的编辑与关闭受保护。
- 当前选择的 Skill 或 Tool 不在目录时拒绝保存并提示调整，不会静默丢弃绑定；同一编辑操作合并即时重复点击，提交和回填前重新检查页面、身份与权限。

旧内置模板的导入与依赖准备入口已移除。自定义 Agent 草稿、实际模型/Skill/Tool 目录及版本治理继续使用正式接口；不会将不可用模板显示为可导入能力。

后台无需新增 SQL；需先配套更新 Runtime 接口。组件验证、构建与真实页面验收分别记录。
