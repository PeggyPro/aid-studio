import type { NextConfig } from 'next'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)

/** 开发代理目标；请在 `.env.development` 中配置 `NEXT_PROXY_TARGET`（与原 Nuxt 项目 NUXT_PROXY_TARGET 语义一致） */
const proxyTarget = process.env.NEXT_PROXY_TARGET || 'http://127.0.0.1:8080'
const canvasWsProxyPort = process.env.AID_DEV_WS_PROXY_PORT || '3015'
const staticExport = process.env.NEXT_STATIC_EXPORT === '1'

const nextConfig: NextConfig = {
  /**
   * 开发态允许通过局域网 IP 访问（Next 16 默认拦截跨域 /_next/*，否则 Network 地址打开会白屏）。
   * 可按本机实际网卡 IP 在 `.env.development` 里追加：NEXT_ALLOWED_DEV_ORIGINS=192.168.x.x,10.x.x.x
   */
  allowedDevOrigins: Array.from(new Set([
    '127.0.0.1',
    ...(process.env.NEXT_ALLOWED_DEV_ORIGINS || '')
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
  ])),
  ...(staticExport
    ? {
        /** 对齐原 `nuxt generate`：输出纯静态文件，发布脚本再归档到 dist/public。 */
        output: 'export' as const,
        trailingSlash: true,
        /**
         * 修复 Next 16 静态导出 RSC 段文件路径与客户端请求不一致（#85374）：
         * 构建产物写成嵌套目录，浏览器请求扁平 `__next.*.txt`。
         */
        adapterPath: require.resolve('./scripts/static-export-rsc-adapter.mjs')
      }
    : {
        /**
         * Skill Runtime events 是 POST SSE。Next 默认 gzip 会把事件缓冲到流结束。
         * 开发环境关闭压缩，并暴露直连地址。
         */
        compress: false,
        env: {
          AID_DEV_PROXY_TARGET: proxyTarget,
          AID_DEV_DIRECTOR_ORIGIN: process.env.AID_DEV_DIRECTOR_ORIGIN || '',
          AID_DEV_WS_PROXY: process.env.AID_DEV_WS_PROXY || '1',
          AID_DEV_WS_PROXY_PORT: canvasWsProxyPort
        },
        /** 开发/Node 运行时：请求走 `/url` 前缀由 Next 代理转发到后端。 */
        async rewrites() {
          return [
            {
              source: '/case/:id(\\d+)',
              destination: '/case?id=:id'
            },
            {
              source: '/discovery/:id(\\d+)',
              destination: '/discovery?id=:id'
            },
            {
              source: '/url/:path*',
              destination: `${proxyTarget.replace(/\/$/, '')}/:path*`
            }
          ]
        }
      }),
  /** 媒体资源来自 OSS 等外部域名，域名不固定，关闭 Next 图片优化以保持与原项目一致的直链行为 */
  images: {
    unoptimized: true
  }
}

export default nextConfig
