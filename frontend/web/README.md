# Web client

Next.js、React、TypeScript 与 Ant Design Web 客户端。运行时需要兼容的后端服务。

## 开发

统一使用 Node.js **22.22.0**、npm **10.9.4**。安装、开发和构建会检查版本；子进程沿用同一 Node。复制 `.env.example` 为 `.env.local`，将 `NEXT_PROXY_TARGET` 指向自己的后端。

```sh
nvm install 22.22.0
nvm use 22.22.0
node --version # v22.22.0
npm --version  # 10.9.4
npm ci
npm run dev
```

如果所选 Node 环境中的 npm 曾被升级，请先在项目目录外执行 `npm install --global npm@10.9.4`，再进入项目安装。项目不会自动切换系统 Node/npm。

## 检查与构建

```sh
npm run typecheck
npm run build
# 静态站点输出至 dist/public
npm run generate
```

开发代理与生产反向代理由部署者配置。模型资源和可选依赖由仓库内 prepare 脚本按配置准备。第三方许可保留在对应资源或组件目录。
