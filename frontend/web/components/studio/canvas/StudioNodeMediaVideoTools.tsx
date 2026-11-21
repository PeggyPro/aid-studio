'use client'

import { useCallback, useState, type ReactNode } from 'react'
import { StudioChromeIcon, type StudioChromeIconName } from '@/components/studio/theme/StudioChromeIcon'
import {
  BorderOutlined,
  ClearOutlined,
  HighlightOutlined,
  AudioOutlined,
  ScissorOutlined,
  SoundOutlined,
  ThunderboltOutlined,
  ToolOutlined
} from '@ant-design/icons'
import { message } from 'antd'
import { useStudioUiStore } from '@/stores/studioUi'
import { useUserStore } from '@/stores/user'
import { acknowledgeStudioDepthIntro, createStudioDepthNodeData, hasSeenStudioDepthIntro } from '@/utils/studio/studioVideoDepth'
import { StudioVideoDepthIntro } from './StudioVideoDepthIntro'
import type { StudioCanvasNode, StudioVideoAudioTool, StudioVideoSubtitleMode } from '@/types/studio'
import { placeStudioNodeWithoutOverlap, toStudioNodeRect } from '@/utils/studio/studioNodePlacement'
import { createStudioVideoClipRange } from '@/utils/studio/studioVideoClip'
import { studioVideoOperationPromptLead } from '@/utils/studio/studioVideoOperationPrompt'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import {
  StudioCanvasDropdownPopover,
  type StudioCanvasDropdownItem
} from './StudioCanvasDropdownMenu'
import { StudioCanvasToolbarButton } from './StudioCanvasToolbarButton'
import { runStudioVideoAudioOperation } from '@/hooks/studio/useStudioVideoAudioOperation'
import { studioVideoAudioToolDefinition } from '@/utils/studio/studioVideoAudioTool'
import { StudioVideoSubjectEditor } from './StudioVideoSubjectEditor'
import type { StudioVideoSubjectEdit, StudioVideoSubjectMode } from '@/types/studio-video-subject'
import { SUBJECT_MODES, subjectEditPrompt, subjectEditRefs } from '@/utils/studio/studioVideoSubject'
import { useStudioVideoOperation } from '@/hooks/studio/useStudioVideoOperation'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { runCanvasVideoAudioSeparation } from '@/hooks/studio/runCanvasVideoAudioSeparation'
import { confirmCanvasVideoToolQuote } from '@/hooks/studio/quoteCanvasVideoToolSelection'
import { canvasResourceMatchesNodeMedia } from '@/utils/canvas/canvasCurrentResultResource'

type VideoToolMenu = 'remove' | 'edit' | 'reshoot' | 'motion' | 'subject'
const SUBJECT_ICONS: Record<StudioVideoSubjectMode, StudioChromeIconName> = {
  remove: 'subjectRemove',
  modify: 'subjectModify',
  replace: 'subjectReplace',
  matting: 'subjectMatting'
}
const audioSubmissionKeys = new Set<string>()

export function StudioNodeMediaVideoTools({ node }: { node: StudioCanvasNode }) {
  const addNode = useStudioUiStore((state) => state.addNode)
  const addConnection = useStudioUiStore((state) => state.addConnection)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const deriveVideoAudioPair = useStudioUiStore((state) => state.deriveVideoAudioPair)
  const openVideoEditSession = useStudioUiStore((state) => state.openVideoEditSession)
  const openVideoClipSession = useStudioUiStore((state) => state.openVideoClipSession)
  const [menuOpen, setMenuOpen] = useState<VideoToolMenu | null>(null)
  const videoOperation = useStudioVideoOperation()
  const canvasRuntime = useCanvasProjectRuntime()
  const [subjectSession, setSubjectSession] = useState<{ source: StudioCanvasNode; mode: StudioVideoSubjectMode } | null>(null)
  const userId = useUserStore((state) => state.user?.id)
  const [introFor, setIntroFor] = useState<{ nodeId: string; userId: typeof userId } | null>(null)
  const introOpen = menuOpen === 'motion' && introFor?.nodeId === node.id && introFor.userId === userId

  const focusNode = useCallback((nodeId: string) => {
    window.requestAnimationFrame(() => {
      window.dispatchEvent(new CustomEvent('studio:focus-node', { detail: { nodeId } }))
    })
  }, [])

  const createDerivedNode = useCallback((data: Parameters<typeof addNode>[2]) => {
    const occupied = useStudioUiStore.getState().nodes.map((item) => toStudioNodeRect(item))
    const position = placeStudioNodeWithoutOverlap(
      { x: node.position.x + 350, y: node.position.y },
      { width: 320, height: 180 },
      occupied
    )
    const targetId = addNode('video', position, data)
    addConnection({ source: node.id, target: targetId, sourceHandle: null, targetHandle: null }, 'reference')
    selectNodes([targetId])
    focusNode(targetId)
    return targetId
  }, [addConnection, addNode, focusNode, node.id, node.position.x, node.position.y, selectNodes])

  const startHd = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源视频')
      return
    }
    createDerivedNode({
      title: '高清',
      subtitle: `由 ${node.data.title} 发起`,
      zoneId: node.data.zoneId,
      status: 'empty',
      mediaKind: 'video',
      videoPurpose: 'hd',
      composerHidden: false,
      storyboardId: node.data.storyboardId,
      videoToolOptions: {
        sourceNodeId: node.id,
        resolution: '1080',
        interpolationMode: 'none',
        frameRate: 60,
        slowMotionFactor: 1
      },
      resultSummary: '配置参数生成高清视频'
    })
  }, [createDerivedNode, node])

  const startSubtitleRemoval = useCallback((mode: StudioVideoSubtitleMode) => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源视频')
      return
    }
    setMenuOpen(null)
    selectNodes([node.id])
    openVideoEditSession({ nodeId: node.id, mode })
    focusNode(node.id)
  }, [focusNode, node, openVideoEditSession, selectNodes])

  const startReshoot = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源视频')
      return
    }
    setMenuOpen(null)
    const promptContext = studioVideoOperationPromptLead('reshoot', node.data.title)
    createDerivedNode({
      title: `${node.data.title}-片段重拍`,
      subtitle: `由 ${node.data.title} 发起`,
      zoneId: node.data.zoneId,
      status: 'empty',
      mediaKind: 'video',
      mediaUrl: node.data.mediaUrl,
      prompt: promptContext,
      composerRefs: [{ ...studioNodeToComposerRef(node), channel: 'mention' }],
      videoPurpose: 'reshoot',
      composerHidden: false,
      storyboardId: node.data.storyboardId,
      videoToolOptions: {
        sourceNodeId: node.id,
        promptContext
      },
      resultSummary: '选择片段并描述需要重拍的内容'
    })
  }, [createDerivedNode, node])

  const startContinuation = useCallback(() => {
    if (!node.data.mediaUrl) {
      message.warning('请先上传或生成源视频')
      return
    }
    setMenuOpen(null)
    selectNodes([node.id])
    openVideoClipSession({
      nodeId: node.id,
      mode: 'continuation',
      range: createStudioVideoClipRange(10),
      snapToSeconds: false,
      loopSelection: false
    })
    focusNode(node.id)
  }, [focusNode, node, openVideoClipSession, selectNodes])

  const startVideoAudioTool = useCallback((operation: StudioVideoAudioTool) => {
    const current = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)
    if (!current?.data.mediaUrl) {
      message.warning('请先上传或生成源视频')
      return
    }
    const label = studioVideoAudioToolDefinition(operation).label
    setMenuOpen(null)
    if (canvasRuntime) {
      const scopeKey = useStudioUiStore.getState().scopeKey
      const requestKey = `${scopeKey}:${current.id}:${operation}`
      if (audioSubmissionKeys.has(requestKey)) return
      audioSubmissionKeys.add(requestKey)
      if (operation === 'separate') {
        void runCanvasVideoAudioSeparation(current, canvasRuntime)
          .catch((error) => { message.error(error instanceof Error ? error.message : '音视频分离失败') })
          .finally(() => { audioSubmissionKeys.delete(requestKey) })
        return
      }
      void (async () => {
      try {
        const toolOperation = operation === 'extract_vocals' ? 'VOICE_ONLY' : 'BACKGROUND_ONLY'
        const modelCode = await confirmCanvasVideoToolQuote({ source: current, targetNodeId: '',
          operation: toolOperation, label }, canvasRuntime)
        if (!modelCode || useStudioUiStore.getState().scopeKey !== scopeKey) return
        const occupied = useStudioUiStore.getState().nodes.map(toStudioNodeRect)
        const position = placeStudioNodeWithoutOverlap(
          { x: current.position.x + 350, y: current.position.y }, { width: 320, height: 180 }, occupied)
        const targetNodeId = addNode('voice', position, {
          title: `${current.data.title}-${operation === 'extract_vocals' ? '人声' : '背景音'}`,
          subtitle: `由 ${current.data.title} 派生`, zoneId: current.data.zoneId,
          status: 'queued', mediaKind: 'audio', composerHidden: true,
          storyboardId: current.data.storyboardId,
          videoToolOptions: { sourceNodeId: current.id },
          resultSummary: `${label}处理中`
        })
        addConnection({ source: current.id, target: targetNodeId, sourceHandle: null, targetHandle: null }, 'reference')
        selectNodes([targetNodeId])
        focusNode(targetNodeId)
        await videoOperation.run({ source: current, targetNodeId,
          operation: toolOperation, label, modelCode })
      } catch (error) {
        message.error(error instanceof Error ? error.message : '音频提取报价失败')
      } finally {
        audioSubmissionKeys.delete(requestKey)
      }
      })()
      return
    }
    const targets = deriveVideoAudioPair(current.id, operation)
    if (!targets) {
      message.error('无法创建媒体处理节点，请刷新后重试')
      return
    }
    focusNode(targets.videoNodeId)
    void runStudioVideoAudioOperation({
      source: current,
      targets,
      operation,
      label
    })
  }, [addConnection, addNode, canvasRuntime, deriveVideoAudioPair, focusNode, node.id, selectNodes, videoOperation])

  const confirmSubjects = async (edit: StudioVideoSubjectEdit): Promise<boolean> => {
    const immediate = edit.mode === 'remove' || edit.mode === 'matting'
    const definition = SUBJECT_MODES[edit.mode]
    const source = subjectSession?.source
    if (!source) return false
    const scopeKey = useStudioUiStore.getState().scopeKey
    let modelCode: string | undefined
    if (immediate && canvasRuntime) {
      try {
        const selected = await confirmCanvasVideoToolQuote({ source, targetNodeId: '',
          operation: definition.operation, label: definition.label,
          clipRange: edit.range, subjectEdits: edit.marks, prompt: subjectEditPrompt(edit) }, canvasRuntime)
        if (!selected) return false
        modelCode = selected
      } catch (error) {
        message.error(error instanceof Error ? error.message : '主体处理报价失败')
        return false
      }
    }
    const state = useStudioUiStore.getState()
    if (state.scopeKey !== scopeKey || state.nodes.find((item) => item.id === source.id)?.data.mediaUrl !== edit.sourceVideoUrl) {
      message.warning('源视频已变化，请重新标记主体')
      return false
    }
    const sourceResource = canvasRuntime?.nodeResources(source.id).find(resource =>
      resource.mediaType === 'VIDEO' && canvasResourceMatchesNodeMedia(resource, source.data))
    setSubjectSession(null)
    const targetNodeId = createDerivedNode({
      title: `${source.data.title}-${definition.label}`, status: immediate ? 'generating' : 'empty', mediaKind: 'video',
      ...(modelCode ? { model: modelCode } : {}),
      videoPurpose: 'subject_edit', videoSubjectEdit: edit,
      prompt: subjectEditPrompt(edit).replace(edit.sourceTitle, `@${edit.sourceTitle}`),
      composerHidden: false,
      composerRefs: subjectEditRefs(edit), storyboardId: source.data.storyboardId,
      videoToolOptions: {
        sourceNodeId: source.id, sourceVideoUrl: edit.sourceVideoUrl, clipRange: edit.range,
        ...(sourceResource ? { sourceResourceId: String(sourceResource.resourceId) } : {})
      },
      resultSummary: immediate ? `${definition.label}处理中` : '确认主体编辑内容后生成视频'
    })
    if (immediate) return videoOperation.run({ source, targetNodeId, operation: definition.operation,
      label: definition.label, modelCode, clipRange: edit.range, subjectEdits: edit.marks, prompt: subjectEditPrompt(edit) })
    return true
  }

  const toolPopover = (
    key: VideoToolMenu,
    label: string,
    icon: ReactNode,
    items: StudioCanvasDropdownItem[],
    onSelect: (itemKey: string) => void
  ) => (
    <StudioCanvasDropdownPopover
      open={menuOpen === key}
      activeKey={key === 'motion' && introOpen ? 'deep' : undefined}
      onOpenChange={(open) => { setMenuOpen(open ? key : null); setIntroFor(null) }}
      placement="bottom"
      ariaLabel={label}
      items={items}
      onSelect={onSelect}
      trigger={<StudioCanvasToolbarButton showLabel label={label} icon={icon} active={menuOpen === key} />}
    />
  )

  return (
    <>
      {subjectSession && <StudioVideoSubjectEditor key={`${subjectSession.source.id}:${subjectSession.mode}`} source={subjectSession.source} mode={subjectSession.mode}
        onClose={() => setSubjectSession(null)} onConfirm={confirmSubjects} />}
      <StudioCanvasToolbarButton
        showLabel
        label="高清"
        icon={<StudioChromeIcon name="videoUpscale" fallback={<ThunderboltOutlined aria-hidden />} />}
        disabled={!node.data.mediaUrl}
        onClick={startHd}
      />
      {toolPopover(
        'remove',
        '智能消除',
        <StudioChromeIcon name="subtitleRemove" fallback={<ClearOutlined aria-hidden />} />,
        [
          { key: 'auto', label: '智能去字幕', icon: <StudioChromeIcon name="subtitleRemove" fallback={<ClearOutlined />} /> },
          { key: 'region', label: '框选去字幕', icon: <StudioChromeIcon name="subtitleRegion" fallback={<BorderOutlined />} /> }
        ],
        (key) => startSubtitleRemoval(key as StudioVideoSubtitleMode)
      )}
      {toolPopover(
        'motion',
        '动作捕捉',
        <StudioChromeIcon name="videoDepth" fallback={<BorderOutlined aria-hidden />} />,
        [
          {
            key: 'deep', label: '深度动作捕捉', icon: <StudioChromeIcon name="videoDepth" fallback={<BorderOutlined />} />, disabled: !node.data.mediaUrl, keepOpenOnSelect: true,
            renderTrigger: (button) => (
              <StudioVideoDepthIntro key={`${userId ?? 'anonymous'}:${node.id}:${introOpen}`} open={introOpen}
                sourceUrl={node.data.mediaUrl || ''}
                onClose={() => { setIntroFor(null); setMenuOpen(null) }}
                onConfirm={() => {
                  const current = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)
                  if (!current?.data.mediaUrl) { message.warning('源视频已失效'); return }
                  acknowledgeStudioDepthIntro(userId)
                  setIntroFor(null)
                  setMenuOpen(null)
                  createDerivedNode(createStudioDepthNodeData(current))
                }}>{button}</StudioVideoDepthIntro>
            )
          },
          { key: 'trim', label: '视频截取', icon: <StudioChromeIcon name="videoTrim" fallback={<ScissorOutlined />} /> },
          { key: 'crop', label: '画面裁剪', icon: <StudioChromeIcon name="videoCrop" fallback={<BorderOutlined />} /> }
        ],
        (key) => {
          if (key === 'deep') {
            const current = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)
            if (!current?.data.mediaUrl) return
            if (!hasSeenStudioDepthIntro(userId)) { setIntroFor({ nodeId: node.id, userId }); return }
            setMenuOpen(null)
            createDerivedNode(createStudioDepthNodeData(current))
            return
          }
          if (key !== 'trim' && key !== 'crop') return
          if (!node.data.mediaUrl) return
          selectNodes([node.id])
          openVideoClipSession({
            nodeId: node.id,
            mode: key,
            range: createStudioVideoClipRange(10),
            snapToSeconds: false,
            loopSelection: false,
            cropRect: { x: 0.1, y: 0.1, w: 0.8, h: 0.8 }
          })
          focusNode(node.id)
        }
      )}
      {toolPopover(
        'edit',
        '工具',
        <StudioChromeIcon name="audioVideoSeparate" fallback={<ToolOutlined aria-hidden />} />,
        [
          { key: 'separate', label: '音视频分离', icon: <StudioChromeIcon name="audioVideoSeparate" fallback={<ToolOutlined />} /> },
          { key: 'extract_vocals', label: '人声提取', icon: <StudioChromeIcon name="vocals" fallback={<AudioOutlined />} /> },
          { key: 'extract_background_audio', label: '背景音提取', icon: <StudioChromeIcon name="backgroundAudio" fallback={<SoundOutlined />} /> }
        ],
        (key) => startVideoAudioTool(key as StudioVideoAudioTool)
      )}
      {toolPopover(
        'reshoot',
        '片段重拍',
        <StudioChromeIcon name="reshoot" fallback={<ScissorOutlined aria-hidden />} />,
        [
          { key: 'reshoot', label: '片段重拍', icon: <StudioChromeIcon name="reshoot" fallback={<ScissorOutlined />} /> },
          { key: 'continuation', label: '智能续写', icon: <StudioChromeIcon name="continuation" fallback={<ScissorOutlined />} /> }
        ],
        (key) => key === 'continuation' ? startContinuation() : startReshoot()
      )}
      {toolPopover('subject', '主体编辑', <StudioChromeIcon name="subjectEdit" fallback={<HighlightOutlined />} />, Object.entries(SUBJECT_MODES).map(([key, value]) => ({ key, label: value.label, icon: <StudioChromeIcon name={SUBJECT_ICONS[key as StudioVideoSubjectMode]} fallback={<HighlightOutlined />} />, disabled: !node.data.mediaUrl })), (key) => {
        const current = useStudioUiStore.getState().nodes.find((item) => item.id === node.id)
        if (!current?.data.mediaUrl) return
        setMenuOpen(null)
        setSubjectSession({ source: structuredClone(current), mode: key as StudioVideoSubjectMode })
      })}
    </>
  )
}

export default StudioNodeMediaVideoTools
