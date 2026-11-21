# 计算节点 CA 证书配置

在计算服务地址下可选择上传 CA 公共证书。通常使用公有 CA 签发证书的节点无需上传；私有 CA 节点上传签发服务证书的 CA。不得上传私钥。仍校验服务证书中的域名或 IP 与访问地址一致。

- 接口 `POST /aid/compute/node/certificate/inspect`，权限 `aid:compute:edit`，请求 `{pem: string}`；响应 `data` 包含规范化 `pem` 和 `certificates`，每项含 `subject`、`issuer`、`sha256`、`notBefore`、`notAfter`。
- 仅接受 PEM 编码的 CA 公共证书，最大 64 KiB、最多 8 张，不接受重复、过期、未生效或非 CA 证书。
- 配置新增可选 `tlsCaPem`，保存在现有版本化配置 JSON 内，无需 SQL；空值使用系统信任库。证书只绑定该节点连接，不安装到操作系统或 JVM 全局信任库。
- 连接检测 `/aid/compute/node/probe` 可传 `tlsCaPem`；空字符串明确使用系统信任，省略且携带已有 `nodeKey` 时复用该节点草稿配置。检测回执绑定操作者、地址和 CA，更换证书或地址必须重新检测。
- 上传、替换或移除只修改表单。保存草稿不修改正在执行任务的版本；激活后新任务使用新版本，既有任务的对账仍使用其固定版本配置。
- 编辑时展示 CA 指纹与有效期。已保存证书过期时仍可打开配置替换，但新检测及保存拒绝过期 CA；不支持跳过 TLS 验证。
