'use client'

/* eslint-disable @next/next/no-img-element -- 素材封面来自项目与官方库的运行时地址。 */
import '../composer/studio-library-modals.css'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Modal, message } from 'antd'
import type { MergedAssetVO, UserAssetCustomTypeItem, UserAssetStyleCategoryItem } from '@/types/business-api'
import type { StudioCanvasNode, StudioComposerRef, StudioSavedAsset } from '@/types/studio'
import { userAssetCustomTypeList, userAssetMergedPage, userAssetStyleCategoryList } from '@/utils/businessApi'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import { mapMergedAssetsToStyleCards } from '@/utils/mapMergedStyleAssets'
import { StudioStyleCard } from '../composer/StudioStyleCard'
import type { StudioMaterialNodeKind } from '@/utils/studio/studioNodeKind'
import {
  mergeStudioMaterialCatalog,
  studioMaterialAssetKey,
  studioMaterialOfficialTypes,
  studioOfficialAssetToNodeData,
  studioStyleCardToNodeData,
  studioCatalogMaterialReference,
  type StudioCatalogMaterialPick
} from '@/utils/studio/studioMaterialLibrary'

type ProjectMedia = 'image' | 'video' | 'audio'
type Section =
  | { source: 'project'; media: ProjectMedia }
  | { source: 'official'; assetType?: string }
  | { source: 'style'; categoryCode?: string }

const PAGE_SIZE = 60
const EMPTY_NODES: StudioCanvasNode[] = []
const EMPTY_ASSETS: StudioSavedAsset[] = []

export type StudioMaterialPick =
  | { kind: 'project'; ref: StudioComposerRef }
  | StudioCatalogMaterialPick

export function StudioMaterialLibraryModal({
  open,
  onOpenChange,
  nodes = EMPTY_NODES,
  assets = EMPTY_ASSETS,
  replacementKind,
  onPick
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  nodes?: StudioCanvasNode[]
  assets?: StudioSavedAsset[]
  replacementKind?: StudioMaterialNodeKind
  onPick: (pick: StudioMaterialPick) => void
}) {
  const [section, setSection] = useState<Section>(() => replacementKind === 'style_library'
    ? { source: 'style' } : replacementKind === 'official_asset'
      ? { source: 'official' } : { source: 'project', media: 'image' })
  const [officialTypes, setOfficialTypes] = useState<UserAssetCustomTypeItem[]>([])
  const [styleCategories, setStyleCategories] = useState<UserAssetStyleCategoryItem[]>([])
  const [catalog, setCatalog] = useState<MergedAssetVO[]>([])
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const loadingRef = useRef(false)
  const requestRef = useRef(0)
  const pickedRef = useRef(false)

  useEffect(() => {
    if (!open) return
    pickedRef.current = false
    let alive = true
    void userAssetCustomTypeList()
      .then((types) => { if (alive) setOfficialTypes(types) })
      .catch(() => { if (alive) setOfficialTypes([]) })
    void userAssetStyleCategoryList()
      .then((rows) => { if (alive) setStyleCategories(rows.filter((item) => item.code && item.label)) })
      .catch(() => { if (alive) setStyleCategories([]) })
    return () => { alive = false }
  }, [open])

  const officialNav = useMemo(() => studioMaterialOfficialTypes(officialTypes), [officialTypes])
  const projectItems = useMemo(() => {
    if (section.source !== 'project') return []
    const mediaKind = section.media
    const saved = assets.filter((asset) => asset.mediaKind === mediaKind || asset.kind === mediaKind)
    const fromNodes = nodes.filter((node) => node.data.kind !== 'style_library' && node.data.mediaUrl && node.data.mediaKind === mediaKind).map((node) => ({
      id: node.id,
      title: node.data.title,
      mediaUrl: node.data.mediaUrl!,
      sourceNodeId: node.id
    }))
    const savedItems = saved.map((asset) => ({
      id: asset.id,
      title: asset.title,
      mediaUrl: asset.mediaUrl,
      sourceNodeId: asset.sourceNodeId
    }))
    return [...new Map([...savedItems, ...fromNodes].map((item) => [`${item.sourceNodeId ?? ''}:${item.mediaUrl}`, item])).values()]
  }, [assets, nodes, section])

  const loadCatalog = useCallback(async (next: Exclude<Section, { source: 'project' }>, nextPage: number) => {
    const request = ++requestRef.current
    loadingRef.current = true
    setLoading(true)
    setError('')
    try {
      let catalogPage = nextPage
      for (;;) {
        const { list, total } = await userAssetMergedPage({
          sourceFlag: next.source === 'official' ? 'official' : undefined,
          assetType: next.source === 'style' ? 'style' : next.assetType,
          categoryCode: next.source === 'style' ? next.categoryCode : undefined,
          pageNum: catalogPage,
          pageSize: PAGE_SIZE
        })
        if (request !== requestRef.current) return
        const items = list.filter((asset) => next.source === 'style' ? asset.assetType === 'style' : asset.assetType !== 'style')
        const more = catalogPage * PAGE_SIZE < total
        // An unfiltered official page can contain only styles. Keep looking
        // before declaring the official material section empty.
        if (!items.length && more) {
          catalogPage += 1
          continue
        }
        setCatalog((current) => mergeStudioMaterialCatalog(nextPage === 1 ? [] : current, items))
        setPage(catalogPage)
        setHasMore(more)
        break
      }
    } catch {
      if (request !== requestRef.current) return
      if (nextPage === 1) setCatalog([])
      setError('素材加载失败，请稍后重试')
    } finally {
      if (request === requestRef.current) {
        loadingRef.current = false
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => {
    if (!open || section.source === 'project') {
      requestRef.current += 1
      loadingRef.current = false
      return
    }
    setCatalog([])
    void loadCatalog(section, 1)
  }, [loadCatalog, open, section])

  const chooseSection = (next: Section) => {
    setCatalog([])
    setPage(1)
    setHasMore(false)
    setError('')
    setSection(next)
  }

  const pickProject = (item: { id: string; title: string; mediaUrl: string; sourceNodeId?: string }) => {
    if (section.source !== 'project' || pickedRef.current) return
    pickedRef.current = true
    const onCanvas = Boolean(item.sourceNodeId && nodes.some((node) => node.id === item.sourceNodeId))
    onPick({
      kind: 'project',
      ref: {
        id: onCanvas ? `node:${item.sourceNodeId}` : `asset:${item.id}`,
        kind: onCanvas ? 'node' : 'asset',
        ...(onCanvas ? { nodeId: item.sourceNodeId } : { assetId: item.id }),
        title: item.title,
        mediaKind: section.media,
        previewUrl: item.mediaUrl
      }
    })
    onOpenChange(false)
  }

  const styleSection = section.source === 'style'
  const catalogItems = catalog.map((asset) => {
    const [style] = styleSection ? mapMergedAssetsToStyleCards([asset]) : []
    return {
      key: studioMaterialAssetKey(asset),
      title: style?.name ?? (asset.assetName || '未命名素材'),
      preview: style?.thumbnail ?? String(asset.imageUrl || ''),
      asset,
      style
    }
  })

  const pickCatalog = ({ asset, style }: (typeof catalogItems)[number]) => {
    if (pickedRef.current) return
    if (style && !style.promptText?.trim()) {
      message.warning('该风格尚未配置中文提示词，请在后台补齐后使用')
      return
    }
    const data = style ? studioStyleCardToNodeData(style) : studioOfficialAssetToNodeData(asset)
    if (!data) { message.warning('该素材暂无可用预览图'); return }
    pickedRef.current = true
    onPick({
      kind: 'node',
      nodeKind: style ? 'style_library' : 'official_asset',
      data,
      ref: studioCatalogMaterialReference(asset, data)
    })
    onOpenChange(false)
  }

  return (
    <Modal
      open={open}
      title={
        <span className="studio-material-library__title">
          {replacementKind ? (replacementKind === 'style_library' ? '替换风格 · 素材库' : '替换素材 · 素材库') : '素材库'}
        </span>
      }
      footer={null}
      width="min(1080px, calc(100vw - 32px))"
      centered
      className="studio-material-library"
      onCancel={() => onOpenChange(false)}
    >
      <div className="studio-material-library__body">
        <nav className="studio-material-library__nav" aria-label="素材来源">
          {!replacementKind ? <>
            <h3>本项目</h3>
            {(['image', 'video', 'audio'] as const).map((media) => (
              <button key={media} type="button" className={section.source === 'project' && section.media === media ? 'is-active' : ''} onClick={() => chooseSection({ source: 'project', media })}>
                {media === 'image' ? '图片' : media === 'video' ? '视频' : '音频'}
              </button>
            ))}
          </> : null}
          {replacementKind !== 'style_library' ? <>
            <h3>官方素材</h3>
            <button type="button" className={section.source === 'official' && !section.assetType ? 'is-active' : ''} onClick={() => chooseSection({ source: 'official' })}>全部</button>
            {officialNav.map((item) => (
              <button key={item.code} type="button" className={section.source === 'official' && section.assetType === item.code ? 'is-active' : ''} onClick={() => chooseSection({ source: 'official', assetType: item.code })}>
                {item.name}
              </button>
            ))}
          </> : null}
          {replacementKind !== 'official_asset' ? <>
            <h3>风格</h3>
            <button type="button" className={section.source === 'style' && !section.categoryCode ? 'is-active' : ''} onClick={() => chooseSection({ source: 'style' })}>全部风格</button>
            {styleCategories.map((item) => (
              <button key={item.code} type="button" className={section.source === 'style' && section.categoryCode === item.code ? 'is-active' : ''} onClick={() => chooseSection({ source: 'style', categoryCode: item.code })}>
                {item.label}
              </button>
            ))}
          </> : null}
        </nav>
        <div className="studio-material-library__main">
          <div className={`studio-material-library__grid${styleSection ? ' studio-material-library__grid--styles' : ''}`} onScroll={(event) => {
            if (section.source === 'project' || loadingRef.current || !hasMore) return
            const node = event.currentTarget
            if (node.scrollHeight - node.scrollTop - node.clientHeight < 160) void loadCatalog(section, page + 1)
          }}>
            {section.source === 'project' ? (
              projectItems.length ? projectItems.map((item) => (
                <button key={`${item.sourceNodeId ?? 'asset'}:${item.id}`} type="button" className="studio-material-library__card" onClick={() => pickProject(item)}>
                  <div className="studio-material-library__preview">
                    {section.media === 'video'
                    ? <video src={`${resolveMediaPlaybackUrl(item.mediaUrl)}#t=0.1`} muted playsInline preload="metadata" />
                    : section.media === 'audio'
                      ? <i>音频</i>
                      : <img src={item.mediaUrl} alt="" loading="lazy" decoding="async" />}
                  </div>
                  <span>{item.title}</span>
                </button>
              )) : <div className="studio-material-library__empty">当前项目还没有这类素材</div>
            ) : error ? <div className="studio-material-library__empty">{error}</div>
              : catalogItems.length ? catalogItems.map((item) => item.style ? <StudioStyleCard key={item.key}
                id={item.style.id} name={item.title} thumbnail={item.preview} featured={item.style.featured} onSelect={() => pickCatalog(item)} /> : (
                <button key={item.key} type="button" className="studio-material-library__card" onClick={() => pickCatalog(item)}>
                  <div className="studio-material-library__preview">
                    {item.preview ? <img src={item.preview} alt="" loading="lazy" decoding="async" /> : <i>无预览</i>}
                  </div>
                  <span>{item.title}</span>
                </button>
              )) : loading ? <div className="studio-material-library__empty">正在加载素材…</div>
                : <div className="studio-material-library__empty">{section.source === 'style' ? '暂无可用风格' : '暂无官方素材'}</div>}
            {loading && catalogItems.length ? <div className="studio-material-library__more">正在加载更多…</div> : null}
          </div>
        </div>
      </div>
    </Modal>
  )
}
