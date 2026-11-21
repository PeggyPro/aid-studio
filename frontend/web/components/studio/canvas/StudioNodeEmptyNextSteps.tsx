'use client'

import {
  Fragment,
  forwardRef,
  memo,
  useCallback,
  useImperativeHandle,
  useRef,
  useState,
  type ChangeEvent,
  type PointerEvent as ReactPointerEvent
} from 'react'
import { LoadingOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { studioNodeEmptyNextSteps } from '@/config/studioNodeEmptyNextSteps'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioNodeKind } from '@/types/studio'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import { runStudioNodeEmptyNextStep } from '@/utils/studio/runStudioNodeEmptyNextStep'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import {
  placeStudioNodeEmptyNextSteps,
  type StudioMediaToolbarRect
} from '@/utils/studio/studioNodeMediaToolbar'

export interface StudioNodeEmptyNextStepsHandle {
  reposition: (selectionRect: StudioMediaToolbarRect, viewportRect: StudioMediaToolbarRect) => void
  containsPoint: (clientX: number, clientY: number) => boolean
}

export const StudioNodeEmptyNextSteps = memo(forwardRef<StudioNodeEmptyNextStepsHandle, {
  nodeId: string
  kind: StudioNodeKind
  onPointerEnter?: (event: ReactPointerEvent<HTMLDivElement>) => void
  onPointerLeave?: (event: ReactPointerEvent<HTMLDivElement>) => void
}>(function StudioNodeEmptyNextSteps({ nodeId, kind, onPointerEnter, onPointerLeave }, ref) {
  const canvasRuntime = useCanvasProjectRuntime()
  const elementRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const steps = studioNodeEmptyNextSteps(kind)

  const reposition = useCallback((selectionRect: StudioMediaToolbarRect, viewportRect: StudioMediaToolbarRect) => {
    const element = elementRef.current
    if (!element) return
    const rail = element.querySelector('.studio-node-empty-next-steps__rail')
    // 只量 rail，避免下方 hover 桥被算进「工具条高度」导致整体上移/错位
    const measured = (rail instanceof HTMLElement ? rail : element).getBoundingClientRect()
    const position = placeStudioNodeEmptyNextSteps(selectionRect, viewportRect, {
      width: Math.max(measured.width, 120),
      height: Math.max(measured.height, 38)
    })
    element.style.left = `${position.left}px`
    element.style.top = `${position.top}px`
    // 外框跟卡片等宽，标签靠 align-items 居中；不要改回 max-content，否则只剩标签自己的宽度。
    element.style.width = `${position.width}px`
    element.style.minWidth = `${position.width}px`
    element.style.paddingBottom = ''
    const bridge = element.querySelector('.studio-node-empty-next-steps__bridge')
    if (bridge instanceof HTMLElement) {
      bridge.style.height = `${position.bridgeHeight}px`
    }
  }, [])

  useImperativeHandle(ref, () => ({
    reposition,
    containsPoint: (clientX, clientY) => {
      const bounds = elementRef.current?.getBoundingClientRect()
      return !!bounds && clientX >= bounds.left && clientX <= bounds.right
        && clientY >= bounds.top && clientY <= bounds.bottom
    }
  }), [reposition])

  if (!steps.length) return null

  const uploadAndApplyImg2Img = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file || uploading) return
    setUploading(true)
    try {
      const [{ readMediaFileAspectRatio }, resource] = await Promise.all([
        import('@/utils/studio/studioMediaFileAspect'),
        canvasRuntime ? canvasRuntime.uploadResource(file, 'IMAGE') : Promise.resolve(null)
      ])
      const mediaUrl = resource?.resourceUrl || await uploadImageToOssWithToast(file, () => undefined)
      if (!mediaUrl) return
      const mediaAspectRatio = await readMediaFileAspectRatio(file)
      const targetId = useStudioUiStore.getState().applyEmptyPreset(nodeId, 'image-to-image', {
        source: {
          mediaUrl,
          mediaKind: 'image',
          mediaAspectRatio: mediaAspectRatio ?? undefined,
          resultSummary: file.name,
          status: 'success',
          ...(resource ? { canvasResourceIds: [String(resource.resourceId)], canvasPrimaryResourceId: String(resource.resourceId) } : {})
        }
      })
      if (!targetId) {
        message.warning('当前节点状态已变化，请重新选择空图片节点')
        return
      }
    } catch (error: unknown) {
      const detail = error as { msg?: string; message?: string }
      message.error(detail.msg || detail.message || '图片上传失败')
    } finally {
      setUploading(false)
    }
  }

  return (
    <div
      ref={elementRef}
      className="studio-node-empty-next-steps"
      aria-label="下一步"
      style={{ left: -9999, top: 0 }}
      onPointerEnter={onPointerEnter}
      onPointerLeave={onPointerLeave}
      onPointerDown={(event) => event.stopPropagation()}
    >
      <span className="sr-only">下一步</span>
      <div className="studio-node-empty-next-steps__rail studio-canvas-glass-toolbar nodrag nowheel">
        {steps.map((step, index) => (
          <Fragment key={step.id}>
            {index > 0 ? <span className="studio-canvas-toolbar__divider" aria-hidden /> : null}
            <StudioComposerChip
              arrow={false}
              disabled={uploading}
              className="studio-node-empty-next-steps__chip nodrag"
              icon={
                uploading && step.action.type === 'upload-then-link-img2img'
                  ? <LoadingOutlined className="studio-node-empty-next-steps__spin" />
                  : undefined
              }
              label={step.label}
              onClick={(event) => {
                event.stopPropagation()
                runStudioNodeEmptyNextStep({
                  nodeId,
                  step,
                  requestImageUpload: () => inputRef.current?.click()
                })
              }}
            />
          </Fragment>
        ))}
      </div>
      {/* 仅保留悬停区域的几何尺寸，不拦截下方标题的点击。 */}
      <div className="studio-node-empty-next-steps__bridge" aria-hidden />
      <input
        ref={inputRef}
        hidden
        type="file"
        accept="image/jpeg,image/png,image/webp"
        onChange={(event) => { void uploadAndApplyImg2Img(event) }}
      />
    </div>
  )
}))

StudioNodeEmptyNextSteps.displayName = 'StudioNodeEmptyNextSteps'
