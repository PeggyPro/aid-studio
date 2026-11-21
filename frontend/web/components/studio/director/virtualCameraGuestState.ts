export type GuestPermissionState = 'requesting' | 'denied' | 'granted' | 'unsupported'
export type GuestSocketState = 'idle' | 'joining' | 'connecting' | 'waiting' | 'connected' | 'error' | 'reset'
export type GuestSceneState = 'loading' | 'ready' | 'error'
export type GuestViewKind = 'need-camera' | 'need-safari' | 'connecting' | 'loading' | 'load-error' | 'disconnected' | 'reset' | 'ready'

export function guestViewStatus({
  permission,
  ws,
  snapshot,
  scene,
}: {
  permission: GuestPermissionState
  ws: GuestSocketState
  snapshot: boolean
  scene: GuestSceneState
}): { kind: GuestViewKind; message: string } {
  if (permission === 'unsupported') return { kind: 'need-safari', message: '当前扫码窗口不支持实时画面，请从电脑重新扫码并用手机浏览器打开' }
  if (permission === 'denied') return { kind: 'need-camera', message: '手机运镜需要HTTPS安全连接' }
  if (permission === 'requesting') return { kind: 'connecting', message: '正在检查安全连接' }
  if (ws === 'reset') return { kind: 'reset', message: '电脑已重置，请重新扫码' }
  if (ws === 'error') return { kind: 'disconnected', message: '连接中断，请保持网络' }
  if (ws === 'joining') return { kind: 'connecting', message: '正在验证扫码配对' }
  if (ws === 'waiting') return { kind: 'connecting', message: '信令已连接，正在建立局域网视频' }
  if (ws !== 'connected') return { kind: 'connecting', message: '正在连接虚拟相机信令' }
  if (!snapshot) return { kind: 'connecting', message: '正在同步电脑机位' }
  if (scene === 'error') return { kind: 'load-error', message: '未收到机位画面，点此重试' }
  if (scene !== 'ready') return { kind: 'loading', message: '正在接收机位画面' }
  return { kind: 'ready', message: '' }
}
