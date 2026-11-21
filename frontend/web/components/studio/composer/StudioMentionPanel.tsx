'use client'

/* eslint-disable @next/next/no-img-element -- 引用面板需预览 blob / 运行时素材 URL。 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useStudioComposerPresentation } from './studioComposerPresentationContext'
import {
  AudioOutlined,
  DownOutlined,
  FileTextOutlined,
  PictureOutlined,
  PlaySquareOutlined,
  RightOutlined,
  SearchOutlined,
  UpOutlined
} from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode, StudioComposerRef, StudioMediaKind, StudioSavedAsset } from '@/types/studio'
import { studioComposerRefFromAsset } from '@/utils/studio/studioConnectionReference'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import { resolveStudioScriptNodeText } from '@/utils/studio/studioScriptNode'
import type { StudioMentionPanelAnchor } from '@/utils/studio/studioMentionAnchor'
import './studio-canvas-dropdown.css'
import { StudioCanvasCollapse } from '@/components/studio/canvas/StudioCanvasCollapse'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

type MentionCategory = 'image' | 'video' | 'audio' | 'text'

interface MentionCandidate {
  id: string
  title: string
  subtitle?: string
  mediaKind: StudioMediaKind
  previewUrl?: string
  ref: StudioComposerRef
}

const CATEGORIES: Array<{
  key: MentionCategory
  label: string
  icon: typeof PictureOutlined
}> = [
  { key: 'image', label: '图片', icon: PictureOutlined },
  { key: 'video', label: '视频', icon: PlaySquareOutlined },
  { key: 'audio', label: '音频', icon: AudioOutlined },
  { key: 'text', label: '文本', icon: FileTextOutlined }
]

const REFERENCED_COLLAPSE_THRESHOLD = 6

function matchesCategory(node: StudioCanvasNode, category: MentionCategory): boolean {
  if (category === 'image') return node.data.kind === 'image' || node.data.mediaKind === 'image'
  if (category === 'video') return node.data.kind === 'video' || node.data.mediaKind === 'video'
  if (category === 'audio') return node.data.kind === 'voice' || node.data.mediaKind === 'audio'
  return node.data.kind === 'text' || node.data.kind === 'note' || node.data.kind === 'storyboard_script'
    || node.data.mediaKind === 'text'
}

function matchesAssetCategory(asset: StudioSavedAsset, category: MentionCategory): boolean {
  if (category === 'image') return asset.kind === 'image' || asset.mediaKind === 'image'
  if (category === 'video') return asset.kind === 'video' || asset.mediaKind === 'video'
  if (category === 'audio') return asset.mediaKind === 'audio'
  return false
}

function hasMentionableContent(node: StudioCanvasNode): boolean {
  if (node.data.kind === 'text' || node.data.kind === 'storyboard_script' || node.data.kind === 'note') {
    return Boolean(resolveStudioScriptNodeText(node.data))
  }
  return Boolean(node.data.mediaUrl?.trim())
}

function previewIcon(mediaKind: StudioMediaKind) {
  if (mediaKind === 'video') return <PlaySquareOutlined />
  if (mediaKind === 'audio') return <AudioOutlined />
  if (mediaKind === 'text') return <FileTextOutlined />
  return <PictureOutlined />
}

function candidateFromNode(node: StudioCanvasNode): MentionCandidate {
  const ref = studioNodeToComposerRef(node)
  return {
    id: `node:${node.id}`,
    title: node.data.title,
    subtitle: '画布节点',
    mediaKind: ref.mediaKind,
    previewUrl: ref.previewUrl,
    ref
  }
}

function candidateFromAsset(asset: StudioSavedAsset): MentionCandidate {
  const ref = studioComposerRefFromAsset(asset)
  return {
    id: `asset:${asset.id}`,
    title: asset.title,
    subtitle: '资产库',
    mediaKind: ref.mediaKind,
    previewUrl: ref.previewUrl,
    ref
  }
}

function MentionPreview({ candidate }: { candidate: MentionCandidate }) {
  if (candidate.previewUrl && (candidate.mediaKind === 'image' || candidate.ref.kind === 'node' && candidate.ref.mediaKind === 'image')) {
    return <img src={candidate.previewUrl} alt="" />
  }
  if (candidate.previewUrl && candidate.mediaKind === 'video') {
    return <video src={candidate.previewUrl} muted preload="metadata" />
  }
  return <i>{previewIcon(candidate.mediaKind)}</i>
}

export function StudioMentionPanel({
  target,
  query,
  anchor,
  onSelect,
  onDismiss,
  onQueryChange,
  exiting = false
}: {
  target: StudioCanvasNode
  query: string
  anchor: StudioMentionPanelAnchor
  onSelect: (ref: StudioComposerRef) => void
  onDismiss: () => void
  onQueryChange: (query: string) => void
  exiting?: boolean
}) {
  const assets = useStudioUiStore((state) => state.savedAssets)
  const presentation = useStudioComposerPresentation()
  /** 面板挂载时拍快照，避免打开期间跟整表 nodes 订阅。 */
  const [nodes] = useState(() => useStudioUiStore.getState().nodes)
  const [panelSearch, setPanelSearch] = useState(query)
  const [activeCategory, setActiveCategory] = useState<MentionCategory>('image')
  const [referencedCollapsed, setReferencedCollapsed] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setPanelSearch(query)
    if (!query.trim()) setActiveCategory('image')
  }, [query])

  useEffect(() => {
    if (exiting) return
    const onPointerDown = (event: PointerEvent) => {
      if (!(event.target instanceof Node)) return
      if (rootRef.current?.contains(event.target)) return
      if (event.target instanceof HTMLElement && event.target.closest('textarea')) return
      if (event.target instanceof HTMLElement && event.target.closest('.studio-composer-mention')) return
      onDismiss()
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onDismiss()
      }
    }
    window.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('keydown', onKeyDown, true)
    return () => {
      window.removeEventListener('pointerdown', onPointerDown, true)
      window.removeEventListener('keydown', onKeyDown, true)
    }
  }, [onDismiss, exiting])

  const keyword = panelSearch.trim().toLowerCase()

  const referenced = useMemo(() => {
    const ids = new Set((target.data.composerRefs ?? []).map((ref) => ref.nodeId).filter(Boolean))
    return nodes
      .filter((node) => ids.has(node.id) && node.id !== target.id && hasMentionableContent(node))
      .map(candidateFromNode)
      .filter((item) => !keyword || item.title.toLowerCase().includes(keyword))
  }, [keyword, nodes, target.data.composerRefs, target.id])

  useEffect(() => {
    if (referenced.length <= REFERENCED_COLLAPSE_THRESHOLD) setReferencedCollapsed(false)
  }, [referenced.length])

  const searchResults = useMemo(() => {
    if (!keyword) return []
    const nodeItems = nodes
      .filter((node) => node.id !== target.id && hasMentionableContent(node))
      .map(candidateFromNode)
      .filter((item) => item.title.toLowerCase().includes(keyword))
    const assetItems = assets
      .map(candidateFromAsset)
      .filter((item) => item.title.toLowerCase().includes(keyword))
    return [...nodeItems, ...assetItems]
  }, [assets, keyword, nodes, target.id])

  const categoryItems = useMemo(() => {
    const nodeItems = nodes
      .filter((node) => node.id !== target.id && matchesCategory(node, activeCategory) && hasMentionableContent(node))
      .map(candidateFromNode)
      .filter((item) => !keyword || item.title.toLowerCase().includes(keyword))
    const assetItems = assets
      .filter((asset) => matchesAssetCategory(asset, activeCategory))
      .map(candidateFromAsset)
      .filter((item) => !keyword || item.title.toLowerCase().includes(keyword))
    return [...nodeItems, ...assetItems]
  }, [activeCategory, assets, keyword, nodes, target.id])

  const secondaryTitle = keyword
    ? `搜索「${panelSearch.trim()}」`
    : CATEGORIES.find((item) => item.key === activeCategory)?.label

  const secondaryItems = keyword ? searchResults : categoryItems
  const showReferencedCollapse = referenced.length > REFERENCED_COLLAPSE_THRESHOLD
  const referencedVisible = !referencedCollapsed || !showReferencedCollapse

  const panel = (
    <div
      ref={rootRef}
      className={`studio-mention-panel studio-mention-panel--${anchor.placement}${exiting ? ' is-exiting' : ''}`}
      inert={exiting}
      aria-hidden={exiting}
      role="listbox"
      aria-label="引用素材"
      style={{ left: anchor.left, top: anchor.top }}
    >
      <div className="studio-mention-panel__menu">
        <div
          className="studio-pane-context-menu__root studio-mention-panel__primary studio-canvas-popover-scroll"
          style={{ maxHeight: anchor.maxHeight }}
        >
          <label className="studio-mention-panel__search">
          <SearchOutlined />
          <input
            value={panelSearch}
            placeholder="搜索"
            onChange={(event) => {
              setPanelSearch(event.target.value)
              onQueryChange(event.target.value)
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                onDismiss()
              }
            }}
          />
        </label>

        {referenced.length ? (
          <>
            <div className="studio-mention-panel__heading studio-mention-panel__heading--row">
              <span>已引用</span>
              {showReferencedCollapse ? (
                <button
                  type="button"
                  className="studio-mention-panel__collapse"
                  aria-label={referencedCollapsed ? '展开已引用' : '折叠已引用'}
                  aria-expanded={!referencedCollapsed}
                  onClick={() => setReferencedCollapsed((value) => !value)}
                >
                  {referencedCollapsed ? <DownOutlined /> : <UpOutlined />}
                </button>
              ) : null}
            </div>
            <StudioCanvasCollapse open={referencedVisible}>
              <div className="studio-mention-panel__references">{referenced.map((item) => (
                <button key={item.id} type="button" onClick={() => onSelect(item.ref)}>
                  <i><MentionPreview candidate={item} /></i>
                  <StudioCanvasTooltip title={item.title} placement="right"><span><strong>{item.title}</strong></span></StudioCanvasTooltip>
                </button>
              ))}</div>
            </StudioCanvasCollapse>
          </>
        ) : null}

        <div className="studio-mention-panel__heading">素材引用</div>
        {CATEGORIES.map((category) => {
          const Icon = category.icon
          return (
            <button
              key={category.key}
              type="button"
              className={activeCategory === category.key && !keyword ? 'is-active' : ''}
              onMouseEnter={() => setActiveCategory(category.key)}
              onFocus={() => setActiveCategory(category.key)}
              onClick={() => setActiveCategory(category.key)}
            >
              <i><Icon /></i>
              <span>{category.label}</span>
              <RightOutlined className="studio-pane-context-menu__arrow" />
            </button>
          )
        })}
      </div>

      {(keyword || activeCategory) ? (
        <div
          className="studio-pane-context-menu__flyout studio-mention-panel__secondary studio-mention-panel__secondary--open studio-canvas-popover-scroll"
          aria-label="素材列表"
          style={{ maxHeight: anchor.maxHeight }}
        >
          {secondaryTitle ? <div className="studio-mention-panel__heading">{secondaryTitle}</div> : null}
          {secondaryItems.length ? secondaryItems.map((item) => (
            <button key={item.id} type="button" onClick={() => onSelect(item.ref)}>
              <i><MentionPreview candidate={item} /></i>
              <span>
                <strong>{item.title}</strong>
                {item.subtitle ? <small>{item.subtitle}</small> : null}
              </span>
            </button>
          )) : (
            <div className="studio-mention-panel__empty">暂无可用素材</div>
          )}
        </div>
      ) : null}
      </div>
    </div>
  )

  if (typeof document === 'undefined') return null
  return createPortal(panel, presentation?.centered ? presentation.getOverlayContainer() ?? document.body : document.body)
}
