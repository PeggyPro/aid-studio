'use client'

import { CloseOutlined } from '@ant-design/icons'
import { forwardRef, memo, useCallback, useImperativeHandle, useRef } from 'react'
import { message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import { placeStudioImageEditToolbar } from '@/utils/studio/studioImageEditToolbarLayout'
import type { StudioAgentDockRect } from '@/utils/studio/studioNodeAgentDockLayout'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { StudioVideoClipTimeline } from '@/components/studio/composer/StudioVideoClipTimeline'
import { StudioVideoTransformDock } from './StudioVideoTransformDock'
import { studioVideoOperationPromptLead } from '@/utils/studio/studioVideoOperationPrompt'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'

export interface StudioVideoClipDockHandle {
  reposition: (selectionRect: StudioAgentDockRect, viewportRect: StudioAgentDockRect, previewRect?: StudioAgentDockRect | null) => void
}

export const StudioVideoClipDock = memo(forwardRef<StudioVideoClipDockHandle>(
  function StudioVideoClipDock(_, ref) {
    const rootRef = useRef<HTMLDivElement>(null)
    const transformRef = useRef<StudioVideoClipDockHandle>(null)
    const session = useStudioUiStore((state) => state.videoClipSession)
    const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === session?.nodeId))
    const patchSession = useStudioUiStore((state) => state.patchVideoClipSession)
    const closeSession = useStudioUiStore((state) => state.closeVideoClipSession)
    const addNode = useStudioUiStore((state) => state.addNode)
    const addConnection = useStudioUiStore((state) => state.addConnection)
    const selectNodes = useStudioUiStore((state) => state.selectNodes)

    const reposition = useCallback((selectionRect: StudioAgentDockRect, viewportRect: StudioAgentDockRect, previewRect?: StudioAgentDockRect | null) => {
      transformRef.current?.reposition(selectionRect, viewportRect, previewRect)
      const root = rootRef.current
      if (!root) return
      const measured = root.getBoundingClientRect()
      const position = placeStudioImageEditToolbar(selectionRect, viewportRect, {
        width: measured.width || 520,
        height: measured.height || 70
      })
      root.style.left = `${position.left}px`
      root.style.top = `${position.top}px`
    }, [])

    useImperativeHandle(ref, () => ({ reposition }), [reposition])

    if (!session || !node || !node.data.mediaUrl) return null
    if (session.mode === 'trim' || session.mode === 'crop') {
      return <StudioVideoTransformDock key={`${node.id}:${session.mode}`} ref={transformRef} node={node} session={session} />
    }

    const confirmContinuation = () => {
      const source = useStudioUiStore.getState().nodes.find((item) => item.id === session.nodeId)
      if (!source?.data.mediaUrl) {
        message.warning('请先上传或生成源视频')
        closeSession()
        return
      }
      const occupied = useStudioUiStore.getState().nodes.map((item) => toStudioNodeRect(item))
      const position = placeStudioNodeWithoutOverlap(
        { x: source.position.x + 350, y: source.position.y },
        { width: 320, height: 180 },
        occupied
      )
      const promptContext = studioVideoOperationPromptLead('continuation', source.data.title)
      const targetId = addNode('video', position, {
        title: `续写 ${source.data.title}`,
        subtitle: `由 ${source.data.title} 发起`,
        zoneId: source.data.zoneId,
        status: 'empty',
        mediaKind: 'video',
        prompt: promptContext,
        composerRefs: [{ ...studioNodeToComposerRef(source), channel: 'mention' }],
        videoPurpose: 'continuation',
        composerHidden: false,
        storyboardId: source.data.storyboardId,
        videoToolOptions: {
          sourceNodeId: source.id,
          clipRange: session.range,
          promptContext
        },
        resultSummary: '填写续写内容后生成视频'
      })
      addConnection({ source: source.id, target: targetId, sourceHandle: null, targetHandle: null }, 'reference')
      closeSession()
      selectNodes([targetId])
      window.requestAnimationFrame(() => {
        window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId: targetId } }))
      })
    }

    return (
      <div
        ref={rootRef}
        className="studio-video-clip-dock nodrag nopan nowheel"
        role="toolbar"
        aria-label="智能续写片段选择"
        style={{ left: -9999, top: 0 }}
        onPointerDown={(event) => event.stopPropagation()}
        onWheel={(event) => event.stopPropagation()}
      >
        <button
          type="button"
          className="studio-video-clip-dock__close"
          aria-label="关闭智能续写片段选择"
          onClick={() => closeSession()}
        >
          <CloseOutlined />
        </button>
        <StudioVideoClipTimeline
          videoUrl={node.data.mediaUrl}
          value={session.range}
          showCount={false}
          onChange={(range) => patchSession({ range })}
        />
        <button
          type="button"
          className="studio-video-clip-dock__confirm"
          onClick={confirmContinuation}
        >
          确认续写
        </button>
      </div>
    )
  }
))
