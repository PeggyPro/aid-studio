import { resolveClientApiUrl } from '~/utils/api'
import { mainAssistantTransport, type AssistantTransport } from '~/utils/business/assistant'

const socketPath = '/ws/canvas/assistant'

/** The flow keeps MAIN auth and scope while subscribing to the existing assistant stream. */
export const flowScreenplayTransport: AssistantTransport = {
  ...mainAssistantTransport,
  websocketPath: socketPath,
  websocketUrl(path) {
    if (!/^\/ws\/canvas\/assistant\?ticket=[A-Za-z0-9_%.-]+$/.test(path)) {
      throw new Error('剧本实时连接入口无效')
    }
    const page = new URL(window.location.href)
    const development = process.env.NODE_ENV === 'development'
    const useDevBridge = development && !['0', 'false'].includes(process.env.AID_DEV_WS_PROXY ?? '')
    const backend = development ? process.env.AID_DEV_PROXY_TARGET : undefined
    if (development && !useDevBridge && !backend) throw new Error('缺少本地实时连接后端地址')
    const url = useDevBridge ? new URL(path, page)
      : backend ? new URL(path, backend) : new URL(resolveClientApiUrl(path), page)
    if (useDevBridge) url.port = process.env.AID_DEV_WS_PROXY_PORT || '3015'
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
      throw new Error('剧本实时连接配置无效')
    }
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    return url.toString()
  },
}
