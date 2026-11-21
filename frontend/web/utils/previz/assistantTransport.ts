import {mainAssistantTransport, type AssistantTransport} from '@/utils/business/assistant';
import {resolveClientApiUrl} from '@/utils/api';

type PrevizRealtimeEnvironment = {
  nodeEnv?: string;
  pageHref?: string;
  proxyTarget?: string;
  wsProxyEnabled?: string;
  wsProxyPort?: string | number;
};

/** Keep the strict ticket route, and use the same Origin-aware dev bridge as HTTP's backend. */
export function previzAssistantWebsocketUrl(path: string, env: PrevizRealtimeEnvironment = {}) {
  if (!/^\/ws\/previz\/assistant\?ticket=[A-Za-z0-9_%.-]+$/.test(path)) throw new Error('三维实时连接入口无效');
  const nodeEnv = env.nodeEnv ?? process.env.NODE_ENV;
  const href = env.pageHref ?? window.location.href;
  const enabled = env.wsProxyEnabled ?? process.env.AID_DEV_WS_PROXY;
  let url: URL;
  if (nodeEnv === 'development' && enabled !== '0' && enabled !== 'false') {
    // npm run dev already owns this proxy. Direct browser -> backend connections carry
    // localhost's Origin and are correctly rejected by the server's origin allowlist.
    const rawPort = env.wsProxyPort ?? process.env.AID_DEV_WS_PROXY_PORT ?? '3015';
    const port = Number(rawPort);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('三维实时连接代理端口无效');
    url = new URL(path, href);
    url.port = String(port);
  } else if (nodeEnv === 'development') {
    // An explicit opt-out is supported for a backend configured to trust this dev Origin.
    const target = env.proxyTarget ?? process.env.AID_DEV_PROXY_TARGET;
    if (!target) throw new Error('实时连接代理已关闭，请配置开发后端地址');
    url = new URL(target);
    const route = new URL(path, href);
    url.pathname = url.pathname.replace(/\/+$/, '') + route.pathname;
    url.search = route.search;
    url.hash = '';
  } else {
    // Production uses exactly the HTTP API origin and deployment prefix, including /aid.
    url = new URL(resolveClientApiUrl(path), href);
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('三维实时连接配置无效');
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}

/** Independent 3D-HUB sessions retain MAIN auth; only the authorized socket route is specialized. */
export const previzAssistantTransport: AssistantTransport = {
  ...mainAssistantTransport,
  websocketPath: '/ws/previz/assistant',
  websocketUrl: previzAssistantWebsocketUrl,
};
