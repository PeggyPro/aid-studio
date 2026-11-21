'use client'

import { CameraOutlined, LoadingOutlined } from '@ant-design/icons'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { PrevizNode, PrevizScene } from '@/types/previz'
import { followCameraSubjects, type FollowCameraSubject } from '@/utils/previz/followCamera'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { Choice } from './PrevizControls'
import './previz-follow-camera.css'

export function PrevizFollowCamera({ scene, node, selectedObjectId, readOnly, onCreate }: {
  scene: PrevizScene; node: PrevizNode; selectedObjectId?: string; readOnly?: boolean
  onCreate: (subject: FollowCameraSubject, signal: AbortSignal, progress: (value: number) => void) => Promise<void>
}) {
  const catalog = useMemo(() => {
    try { return { ...followCameraSubjects(scene, node, selectedObjectId), error: '' } }
    catch (cause) { return { items: [], suggested: '', error: cause instanceof Error ? cause.message : '无法读取模型对象' } }
  }, [scene, node, selectedObjectId])
  const [chosen, setChosen] = useState<string | null>(null)
  const subject = chosen ?? catalog.suggested
  const [progress, setProgress] = useState<number | null>(null)
  const [error, setError] = useState('')
  const operation = useRef<AbortController | null>(null)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; operation.current?.abort() } }, [])
  const reason = readOnly ? '当前工程为只读，不能添加机位' : catalog.error || (!catalog.items.some(item => item.key === subject) ? '请先选择需要跟随的对象' : scene.timeline.frameCount > 1800 ? '请先将预演时长调整到 60 秒以内' : '')
  const create = async () => {
    if (operation.current || reason) return
    const controller = new AbortController()
    operation.current = controller; setError(''); setProgress(0)
    try {
      await onCreate({ nodeId: node.id, objectId: catalog.items.find(item => item.key === subject)?.objectId }, controller.signal,
        value => { if (mounted.current && !controller.signal.aborted) setProgress(value) })
    } catch (cause) {
      if (mounted.current && !controller.signal.aborted) setError(cause instanceof Error ? cause.message : '无法添加跟随机位')
    } finally {
      if (operation.current === controller) { operation.current = null; if (mounted.current) setProgress(null) }
    }
  }
  return <section className="pv-follow-camera" aria-label="添加跟随机位">
    <Choice showLabel label="跟随对象" value={subject || '@choose'} disabled={progress !== null}
      items={[...(!subject ? [{ key: '@choose', label: '请选择运动对象' }] : []), ...catalog.items]}
      onChange={value => { if (value !== '@choose') { setChosen(value); setError('') } }} />
    <div className="pv-follow-camera__actions">
      <StudioCanvasTooltip title={reason || '读取当前动画，按当前观察方向生成位置与瞄准轨道'}>
        <button type="button" className="pv-secondary pv-wide" aria-label="一键添加跟随机位" aria-busy={progress !== null} disabled={Boolean(reason) || progress !== null} onClick={() => void create()}>
          {progress !== null ? <LoadingOutlined spin aria-hidden /> : <CameraOutlined aria-hidden />}
          {progress !== null ? `生成跟随轨道 ${Math.round(progress * 100)}%` : '一键添加跟随机位'}
        </button>
      </StudioCanvasTooltip>
      {progress !== null ? <button type="button" className="pv-secondary" onClick={() => operation.current?.abort()}>取消</button> : null}
    </div>
    <p className="pv-help">沿当前动画生成可编辑机位，无需画轨迹。修改源动画后可重新添加。</p>
    {error || catalog.error ? <p className="pv-follow-camera__error" role="alert">{error || catalog.error}</p> : null}
  </section>
}
