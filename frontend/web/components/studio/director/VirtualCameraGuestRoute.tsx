'use client'
import { useEffect, useState } from 'react'
import type { StudioFreeTheme } from '@/utils/studio/studioFreeTheme'
import { readDirectorCameraPairing } from '@/utils/studio/directorCameraPairing'
import { StudioFreeThemeScope } from '@/components/studio/theme/StudioFreeThemeProvider'
import { VirtualCameraGuestShell } from './VirtualCameraGuestShell'
export function VirtualCameraGuestRoute() {
  const [pair, setPair] = useState<ReturnType<typeof readDirectorCameraPairing> | null>(null)
  const [hostTheme, setHostTheme] = useState<StudioFreeTheme>()
  useEffect(() => {
    const readPair = () => {
      const hash = location.hash
      if (!hash) {
        setPair(current => current ?? readDirectorCameraPairing(''))
        return
      }
      setHostTheme(undefined)
      setPair(readDirectorCameraPairing(hash))
      history.replaceState(history.state, '', `${location.pathname}${location.search}`)
    }
    readPair()
    window.addEventListener('hashchange', readPair)
    return () => window.removeEventListener('hashchange', readPair)
  }, [])
  return <StudioFreeThemeScope themeOverride={hostTheme ?? pair?.theme}>
    {pair?.sid && pair.code ? <VirtualCameraGuestShell sessionId={pair.sid} token={pair.code} onThemeChange={setHostTheme} />
      : <main className="virtual-camera-shell"><div className="virtual-camera-status">
        <div role="status" className="virtual-camera-status__card">
          <strong>虚拟相机</strong>
          <p>{pair === null ? '正在读取配对信息…' : '扫码链接缺少配对信息，请用电脑上的二维码重新扫码'}</p>
        </div>
      </div></main>}
  </StudioFreeThemeScope>
}
