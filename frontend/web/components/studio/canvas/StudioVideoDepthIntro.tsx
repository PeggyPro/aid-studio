'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { useRef, useState, type ReactNode } from 'react'
import { STUDIO_DEPTH_DEMO } from '@/utils/studio/studioVideoDepth'
import { inspectMediaUrl } from '@/utils/mediaUrlPolicy'
import '../composer/studio-video-depth.css'

function DepthDemo({ sourceUrl }: { sourceUrl: string }) {
  const afterRef = useRef<HTMLVideoElement>(null)
  const [beforeReady, setBeforeReady] = useState(false)
  const [afterReady, setAfterReady] = useState(false)
  const [failed, setFailed] = useState(false)
  const hasPair = Boolean(STUDIO_DEPTH_DEMO.before && STUDIO_DEPTH_DEMO.after)
  const beforeUrl = hasPair ? STUDIO_DEPTH_DEMO.before : sourceUrl
  const afterUrl = STUDIO_DEPTH_DEMO.after
  const comparing = hasPair && beforeReady && afterReady && !failed
  return (
    <div className={`studio-video-depth-demo${comparing ? ' is-comparing' : ''}`} aria-label="深度动作捕捉视频演示">
      <video
        src={inspectMediaUrl(beforeUrl).allowed ? beforeUrl : ''}
        autoPlay muted loop playsInline preload="auto"
        onCanPlay={() => setBeforeReady(true)}
        onError={() => setFailed(true)}
        onTimeUpdate={(event) => {
          const after = afterRef.current
          if (after && afterReady && Math.abs(after.currentTime - event.currentTarget.currentTime) > 0.25) {
            after.currentTime = event.currentTarget.currentTime
          }
        }}
      />
      <span className="studio-video-depth-demo__label">{hasPair ? '原视频' : '当前源视频'}</span>
      {hasPair && !failed ? (
        <div className="studio-video-depth-demo__after">
          <video ref={afterRef} src={inspectMediaUrl(afterUrl).allowed ? afterUrl : ''}
            autoPlay muted loop playsInline preload="auto"
            onCanPlay={() => setAfterReady(true)} onError={() => setFailed(true)} />
          <span>深度动作捕捉</span>
        </div>
      ) : null}
      {failed ? <p role="status">演示视频暂时无法播放</p> : !hasPair ? <p>前后对比演示素材待配置</p> : null}
    </div>
  )
}

/** 子弹层锚定完整菜单项，由同一个画布 Popover 定位系统处理缩放和边界翻转。 */
export function StudioVideoDepthIntro({ open, sourceUrl, onClose, onConfirm, children }: {
  open: boolean
  sourceUrl: string
  onClose: () => void
  onConfirm: () => void
  children: ReactNode
}) {
  const confirming = useRef(false)
  return (
    <StudioCanvasPopover open={open} trigger={[]} placement="rightTop" arrow={false} destroyOnHidden
      getPopupContainer={(element) => element.closest('.studio-canvas') ?? document.body}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas studio-video-depth-popover' }}
      content={open ? (
        <section className="studio-video-depth-intro nodrag nowheel" role="dialog" aria-label="深度动作捕捉介绍"
          onPointerDown={(event) => event.stopPropagation()}
          onKeyDown={(event) => { event.stopPropagation(); if (event.key === 'Escape') onClose() }}>
          <DepthDemo sourceUrl={sourceUrl} />
          <div className="studio-video-depth-intro__body">
            <strong>深度动作捕捉</strong>
            <p>提取视频深度信息，为镜头运动、人物动作和空间关系提供参考，减少原视频细节对生成结果的干扰。</p>
            <button type="button" autoFocus className="studio-video-depth__confirm" onClick={() => {
              if (confirming.current) return
              confirming.current = true
              onConfirm()
            }}>知道了</button>
          </div>
        </section>
      ) : null}>
      <span className="studio-video-depth-intro__trigger">{children}</span>
    </StudioCanvasPopover>
  )
}
