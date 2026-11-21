'use client'

import { useEffect, useRef, useState, type RefObject } from 'react'
import { AppstoreAddOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { App, Button, Input } from 'antd'
import type { DirectorSceneData } from '@/types/studio'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'
import { addDirectorModelAssetInstance } from '@/utils/studio/directorModelAssets'
import { canvasModelCategories, canvasModelList, canvasModelUse, type CanvasLibraryModel } from '@/utils/canvas/canvasModelLibrary'
import { StudioCanvasDropdownPopover } from '../canvas/StudioCanvasDropdownMenu'
import { StudioPanelSelectTrigger } from '../composer/StudioPanelSelectTrigger'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { useStudioUiStore } from '@/stores/studioUi'
import { useInfiniteScrollPagination } from '@/hooks/useInfiniteScrollPagination'
import { InfiniteScrollLoadFooter } from '@/components/common/InfiniteScrollLoadFooter'
import './director-model-library.css'

export function DirectorBuiltinModelLibrary({ scene, onChange, onSelectObject, scrollRoot }: {
  scene: DirectorSceneData; onChange: (scene: DirectorSceneData) => void; onSelectObject: (id: string | null) => void
  scrollRoot: RefObject<HTMLDivElement | null>
}) {
  const { message } = App.useApp()
  const runtime = useCanvasProjectRuntime()
  const sceneRef = useRef(scene)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => { sceneRef.current = scene }, [scene])
  const [keyword, setKeyword] = useState('')
  const [search, setSearch] = useState('')
  const [categories, setCategories] = useState<Array<{ id: string; name: string }>>([])
  const [categoryId, setCategoryId] = useState(''), [categoryOpen, setCategoryOpen] = useState(false)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const lock = useRef(false)
  const { items, loading, loadingMore, initialLoaded, loadError, hasMore, loadNextPage, setLoadMoreTrigger } = useInfiniteScrollPagination(
    scrollRoot,
    async (pageNum, pageSize) => {
      const rows = await canvasModelList({ pageNum, pageSize, categoryId: categoryId || undefined, keyword: search || undefined })
      return { rows, hasMore: rows.length === pageSize }
    },
    { pageSize: 10, appendDelayMs: 250, onError: reason => setError(reason instanceof Error ? reason.message : '模型列表读取失败') },
  )
  const models = [...new Map(items.map(model => [model.id, model])).values()]
  useEffect(() => {
    let active = true
    void canvasModelCategories().then(groups => { if (active) setCategories(groups) })
      .catch(() => { if (active) message.warning('模型分类读取失败，仍可按名称搜索') })
    return () => { active = false }
  }, [message])
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(keyword.trim()), 250)
    return () => window.clearTimeout(timer)
  }, [keyword])
  useEffect(() => {
    setError('')
    void loadNextPage(true)
  }, [categoryId, search, loadNextPage])
  const add = async (model: CanvasLibraryModel) => {
    if (lock.current) return
    if (!runtime?.project) { message.error('请先打开可编辑画布项目'); return }
    const initialState = useStudioUiStore.getState()
    const scope = initialState.scopeKey, sceneId = sceneRef.current.id, openNodeId = initialState.directorOpenNodeId
    lock.current = true; setBusy(model.id)
    try {
      const assetId = `library-${model.id}`
      const existing = sceneRef.current.modelAssets?.find(asset => asset.id === assetId && asset.resourceId && asset.bundle)
      // One immutable library version has one registration per workspace/user, including response-loss retries.
      const registered = existing ? { resourceId: existing.resourceId!, bundle: existing.bundle! }
        : await canvasModelUse(runtime.project.workspace.workspaceId, model.id, `library_${model.id}`)
      const state = useStudioUiStore.getState()
      if (!mounted.current || state.scopeKey !== scope || sceneRef.current.id !== sceneId
        || state.directorOpenNodeId !== openNodeId || !state.nodes.some(node => node.id === openNodeId)) return
      const result = addDirectorModelAssetInstance(sceneRef.current, {
        id: assetId, resourceId: String(registered.resourceId), bundle: registered.bundle,
        name: model.name, fileName: 'model.glb', url: registered.bundle.files.find(file => file.path === registered.bundle.rootPath)?.resourceUrl || '',
        format: 'glb', kind: JSON.parse(model.metadataJson || '{}').skinned ? 'actor' : 'prop', source: 'library', byteSize: model.fileSize, createdAt: new Date().toISOString()
      })
      sceneRef.current = result.scene; onChange(result.scene); onSelectObject(result.object.id)
    } catch (error) { if (mounted.current && useStudioUiStore.getState().scopeKey === scope) message.error(error instanceof Error ? error.message : '模型添加失败') }
    finally { lock.current = false; if (mounted.current) setBusy('') }
  }
  return <section className="director-builtin-models" aria-label="官方模型库">
    <div className="director-model-filters">
    <Input className="director-model-search" prefix={<SearchOutlined aria-hidden />} aria-label="搜索官方模型" placeholder="搜索名称或标签" value={keyword} onChange={event => setKeyword(event.target.value)} allowClear />
    <StudioCanvasDropdownPopover open={categoryOpen} onOpenChange={setCategoryOpen} ariaLabel="筛选模型分类"
      items={[{ key: '', label: '全部分类' }, ...categories.map(category => ({ key: category.id, label: category.name }))]} activeKey={categoryId}
      onSelect={key => { setCategoryId(key); setCategoryOpen(false) }}
      trigger={<StudioPanelSelectTrigger className="director-model-category" label={categories.find(category => category.id === categoryId)?.name || '全部分类'} ariaLabel="筛选模型分类" aria-haspopup="menu" aria-expanded={categoryOpen} />} />
    </div>
    {initialLoaded && !loading && !loadError && !models.length ? <div className="director-model-empty" role="status"><AppstoreAddOutlined aria-hidden /><span>暂无匹配模型</span><small>试试其他名称或分类</small></div> : null}
    {[...new Set(models.map(model => model.categoryName))].map(category => <section key={category}>
      <h4>{category}</h4><div className="director-choice-list">{models.filter(model => model.categoryName === category).map(model => <StudioCanvasTooltip key={model.id} title={`添加${model.name} · 版本${model.versionNo}`} placement="right">
        <button type="button" disabled={Boolean(busy) || loading} aria-label={`添加官方${model.name}`} onClick={() => void add(model)}>
          {model.thumbnailUrl ? <img src={model.thumbnailUrl} alt="" width={32} height={32} loading="lazy" /> : <AppstoreAddOutlined />}
          <span>{model.name}</span><em>{busy === model.id ? '…' : '+'}</em>
        </button>
      </StudioCanvasTooltip>)}</div>
    </section>)}
    {loadError && !loadingMore ? <div className="director-model-load-error" role="alert">
      <strong>模型暂时无法加载</strong><span>{error || '请稍后重新加载模型列表'}</span><Button icon={<ReloadOutlined />} aria-label="重试加载模型" onClick={() => void loadNextPage(!models.length)}>重新加载</Button>
    </div> : null}
    {initialLoaded && hasMore && !loading && !loadingMore && !loadError ? <div className="director-model-load-trigger" ref={setLoadMoreTrigger} aria-hidden="true" /> : null}
    <InfiniteScrollLoadFooter loading={loading || loadingMore} hasMore={hasMore} hasItems={models.length > 0} loadingText="正在加载模型…" endText="已加载全部模型" />
  </section>
}
