'use client'

/* eslint-disable @next/next/no-img-element -- 画布封面来自业务 CDN，需保留原图加载行为。 */

import { ArrowRightOutlined, DeleteOutlined, EditOutlined, EllipsisOutlined, ImportOutlined, LoadingOutlined, PictureOutlined } from '@ant-design/icons'
import { Button, Dropdown, Input, Modal, Pagination, Select, message } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { useRouter } from 'next/navigation'
import { canvasPublicationStateLabel } from '@/utils/canvas/canvasPublicationState'
import type { CanvasProjectSummary } from '@/types/canvas-api'
import { canvasProjectImport, canvasProjectList, canvasProjectUpdate } from '@/utils/business/canvas'
import { uploadImageToOssWithToast } from '@/utils/ossUpload'
import { apiErrorMessage } from '@/utils/apiError'
import { canvasOperationId } from '@/utils/canvas/canvasId'
import { canvasRetryLedger } from '@/utils/canvas/canvasRetryLedger'
import { stableCanvasJson } from '@/utils/canvas/canvasWorkspaceAdapter'
import { buildLegacyCanvasImport, listLegacyCanvasCandidates, type LegacyCanvasCandidate } from '@/utils/canvas/canvasLegacyImport'
import { HomeCanvasNodeThumb } from '~/components/home/theme/HomeCanvasNodeThumb'
import WorksLibraryAddCard from '@/components/home/WorksLibraryAddCard'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { emptyImageIconUrl } from '@/utils/emptyImageIcon'
import { useCreateDefaultCanvasProject } from '@/hooks/useCreateDefaultCanvasProject'
import { openCanvasProjectPage, reserveCanvasProjectPage } from '@/utils/canvas/canvasProjectNavigation'
import { deleteCanvasProjectAfterCancellingQueuedTasks } from '@/utils/canvas/canvasProjectDeletion'
import './canvas-project-collection.css'

const PAGE_SIZE = 24

function publicationCaption(project: CanvasProjectSummary, badge = false) {
  const state = project.publicationState
  const label = canvasPublicationStateLabel(state)
  const publicLink = state?.isPublic && state.publishedId ? (
    <a href={`/canvas/work?publicationId=${encodeURIComponent(state.publishedId)}`} target="_blank" rel="noopener noreferrer"
      onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()}>公开作品</a>
  ) : null
  if (badge) return (
    <small className="canvas-project-card__publication" title={state?.isDerivative ? `${label} · 复制衍生` : undefined}>
      {publicLink ?? label}
    </small>
  )
  return <small>{label}{state?.isDerivative ? ' · 复制衍生' : ''}{publicLink ? <> · {publicLink}</> : null}</small>
}

function projectIdOf(project: CanvasProjectSummary): string {
  return String(project.projectId)
}

function HomeRecentPlusIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <path d="M9 3.25v11.5M3.25 9h11.5" />
    </svg>
  )
}

function CanvasProjectActions({
  project,
  variant,
  busy,
  onCover,
  onRename,
  onDelete
}: {
  project: CanvasProjectSummary
  variant: 'home' | 'library'
  busy: boolean
  onCover: () => void
  onRename: () => void
  onDelete: () => void
}) {
  if (project.roleCode !== 'OWNER') return null
  const name = project.projectName || '未命名画布'
  return (
    <div className="canvas-project-card__actions-wrap" onClick={(event) => event.stopPropagation()}>
      <Dropdown
        trigger={['click']}
        placement="bottomRight"
        rootClassName={`canvas-project-actions-menu canvas-project-actions-menu--${variant}`}
        menu={{
          items: [
            { key: 'cover', icon: <PictureOutlined />, label: '修改封面' },
            { key: 'rename', icon: <EditOutlined />, label: '重命名' },
            { key: 'delete', icon: <DeleteOutlined />, label: '删除', danger: true }
          ],
          onClick: ({ key, domEvent }) => {
            domEvent.stopPropagation()
            if (key === 'cover') onCover()
            else if (key === 'rename') onRename()
            else if (key === 'delete') onDelete()
          }
        }}
      >
        <button
          type="button"
          className="canvas-project-card__actions"
          aria-label={`${name}的更多操作`}
          disabled={busy}
          onClick={(event) => event.stopPropagation()}
        >
          {busy ? <LoadingOutlined spin /> : <EllipsisOutlined />}
        </button>
      </Dropdown>
    </div>
  )
}

type CanvasProjectCollectionProps = {
  variant: 'home' | 'library'
  enabled?: boolean
  /** 作品库页把搜索放进顶栏。传入后列表不再自带搜索框。 */
  query?: string
  onQueryChange?: (value: string) => void
  /** 把「导入本地画布」交给作品库顶栏，避免列表再长出第二行工具条。 */
  onToolbarExtra?: (node: ReactNode | null) => void
}

function formatCanvasUpdatedAt(value?: string | null) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.slice(0, 10)
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

export function CanvasProjectCollection(props: CanvasProjectCollectionProps) {
  return <CanvasProjectCollectionContent {...props} />
}

function CanvasProjectCollectionContent({
  variant,
  enabled = true,
  query: queryProp,
  onQueryChange,
  onToolbarExtra
}: CanvasProjectCollectionProps) {
  const router = useRouter()
  const { createAndEnter, creating } = useCreateDefaultCanvasProject()
  const [items, setItems] = useState<CanvasProjectSummary[]>([])
  const [loading, setLoading] = useState(enabled)
  const [internalQuery, setInternalQuery] = useState('')
  const query = queryProp ?? internalQuery
  const setQuery = onQueryChange ?? setInternalQuery
  const searchOwnedByParent = queryProp !== undefined
  const [pageNum, setPageNum] = useState(1)
  const [pageQuery, setPageQuery] = useState(query)
  if (pageQuery !== query) {
    setPageQuery(query)
    setPageNum(1)
  }
  const [total, setTotal] = useState(0)
  const [legacyCandidates, setLegacyCandidates] = useState<LegacyCanvasCandidate[]>([])
  const [importCandidate, setImportCandidate] = useState<LegacyCanvasCandidate | null>(null)
  const [importing, setImporting] = useState(false)
  const [renameProject, setRenameProject] = useState<CanvasProjectSummary | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [busyProjectId, setBusyProjectId] = useState<string | null>(null)
  const [loadedCovers, setLoadedCovers] = useState<Record<string, boolean>>({})
  const coverInputRef = useRef<HTMLInputElement | null>(null)
  const coverTargetRef = useRef<CanvasProjectSummary | null>(null)
  const requestSequenceRef = useRef(0)

  const load = useCallback(async (search = '', pageNumber = 1) => {
    if (!enabled) {
      setItems([])
      setLoading(false)
      return
    }
    const sequence = ++requestSequenceRef.current
    setLoading(true)
    try {
      const page = await canvasProjectList({
        projectName: search.trim() || undefined,
        sortBy: 'UPDATED_AT',
        sortDirection: 'DESC',
        pageNum: variant === 'home' ? 1 : pageNumber,
        pageSize: variant === 'home' ? 4 : PAGE_SIZE
      })
      if (sequence === requestSequenceRef.current) {
        setItems(Array.isArray(page?.items) ? page.items : [])
        setTotal(Number(page?.total) || 0)
      }
    } catch (error: unknown) {
      if (sequence !== requestSequenceRef.current) return
      const detail = error as { msg?: string; message?: string }
      message.error(detail?.msg || detail?.message || '查询自由画布失败')
      setItems([])
      setTotal(0)
    } finally {
      if (sequence === requestSequenceRef.current) setLoading(false)
    }
  }, [enabled, variant])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(query, pageNum), variant === 'library' ? 350 : 0)
    return () => window.clearTimeout(timer)
  }, [load, pageNum, query, variant])

  useEffect(() => {
    if (variant !== 'library' || !enabled) return
    const timer = window.setTimeout(() => {
      try { setLegacyCandidates(listLegacyCanvasCandidates(window.localStorage)) }
      catch { setLegacyCandidates([]) }
    }, 0)
    return () => window.clearTimeout(timer)
  }, [enabled, variant])

  useEffect(() => {
    if (!onToolbarExtra) return
    if (variant !== 'library' || legacyCandidates.length === 0) {
      onToolbarExtra(null)
      return
    }
    onToolbarExtra(
      <Button icon={<ImportOutlined />} onClick={() => setImportCandidate(legacyCandidates[0])}>导入本地画布</Button>
    )
    return () => onToolbarExtra(null)
  }, [legacyCandidates, onToolbarExtra, variant])

  async function importLegacy() {
    if (!importCandidate || importing) return
    const page = reserveCanvasProjectPage()
    setImporting(true)
    try {
      const candidateRequest = buildLegacyCanvasImport(importCandidate, canvasOperationId('import'))
      const fingerprint = stableCanvasJson(Object.fromEntries(
        Object.entries(candidateRequest).filter(([key]) => key !== 'operationId')
      ))
      const retryScope = `project:import:${importCandidate.scopeKey}`
      const request = { ...candidateRequest, operationId: canvasRetryLedger.getOrCreate(retryScope, fingerprint, () => candidateRequest.operationId) }
      const result = await canvasProjectImport(request)
      canvasRetryLedger.clear(retryScope, fingerprint)
      if (Object.keys(result.idMap || {}).length !== request.objects.length) {
        message.warning('项目已创建，但对象映射不完整，请进入项目核对内容')
      } else {
        message.success('本地画布已导入')
      }
      setImportCandidate(null)
      page.assign(String(result.projectId))
    } catch (error: unknown) {
      page.cancel()
      const detail = error as { msg?: string; message?: string }
      message.error(detail?.msg || detail?.message || '导入失败，可重试本次请求')
    } finally {
      setImporting(false)
    }
  }

  const visibleItems = useMemo(
    () => variant === 'home' ? items.slice(0, 4) : items,
    [items, variant]
  )

  function remove(project: CanvasProjectSummary) {
    Modal.confirm({
      title: '删除自由画布',
      content: `确认删除《${project.projectName || '未命名画布'}》吗？可取消的排队任务会先停止，删除后无法恢复。`,
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      className: 'create-flow-modal',
      async onOk() {
        try {
          const result = await deleteCanvasProjectAfterCancellingQueuedTasks(projectIdOf(project), String(project.workspaceId))
          message.success(result.cancelledTaskCount > 0
            ? `已停止 ${result.cancelledTaskCount} 个排队任务并删除自由画布`
            : '自由画布已删除')
          await load(query, pageNum)
        } catch (error: unknown) {
          const detail = error as { msg?: string; message?: string }
          message.error(detail?.msg || detail?.message || '删除自由画布失败')
        }
      }
    })
  }

  function beginRename(project: CanvasProjectSummary) {
    setRenameValue(project.projectName || '')
    setRenameProject(project)
  }

  async function saveRename() {
    if (!renameProject || busyProjectId) return
    const name = renameValue.trim()
    if (!name) {
      message.warning('请输入画布名称')
      return
    }
    if (name === renameProject.projectName) {
      setRenameProject(null)
      return
    }
    setBusyProjectId(projectIdOf(renameProject))
    try {
      await canvasProjectUpdate({ projectId: projectIdOf(renameProject), projectName: name })
      message.success('画布已重命名')
      setRenameProject(null)
      await load(query, pageNum)
    } catch (error: unknown) {
      message.error(apiErrorMessage(error, '重命名失败'))
    } finally {
      setBusyProjectId(null)
    }
  }

  async function updateCover(file: File | undefined) {
    const project = coverTargetRef.current
    coverTargetRef.current = null
    if (!project || !file) return
    if (!file.type.startsWith('image/')) {
      message.error('请选择图片文件')
      return
    }
    setBusyProjectId(projectIdOf(project))
    try {
      const coverUrl = await uploadImageToOssWithToast(file)
      if (!coverUrl) return
      if (coverUrl.length > 500) throw new Error('封面地址过长，请更换图片')
      await canvasProjectUpdate({ projectId: projectIdOf(project), coverUrl })
      message.success('封面已更新')
      await load(query, pageNum)
    } catch (error: unknown) {
      message.error(apiErrorMessage(error, '更新封面失败'))
    } finally {
      setBusyProjectId(null)
    }
  }

  function openProjectFromKey(event: KeyboardEvent<HTMLElement>, projectId: string) {
    if (event.target !== event.currentTarget) return
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    openCanvasProjectPage(projectId)
  }

  function projectActions(project: CanvasProjectSummary) {
    return (
      <CanvasProjectActions
        project={project}
        variant={variant}
        busy={busyProjectId === projectIdOf(project)}
        onCover={() => {
          coverTargetRef.current = project
          coverInputRef.current?.click()
        }}
        onRename={() => beginRename(project)}
        onDelete={() => remove(project)}
      />
    )
  }

  const libraryCards = (
    <div className="works-lib-grid canvas-project-grid">
      <WorksLibraryAddCard
        label={creating ? '正在创建…' : '新建画布'}
        disabled={creating}
        icon={creating ? <LoadingOutlined spin /> : undefined}
        onClick={() => void createAndEnter()}
      />
      {loading
        ? Array.from({ length: 4 }, (_, index) => (
          <article key={`canvas-skeleton-${index}`} className="works-lib-card canvas-project-card" aria-hidden="true">
            <div className="works-lib-card__cover works-lib-card__cover--placeholder" />
            <div className="works-lib-card__body" />
          </article>
        ))
        : visibleItems.map((project) => {
          const projectId = projectIdOf(project)
          const projectName = project.projectName || '未命名画布'
          const coverUrl = project.coverUrl?.trim() || ''
          return (
            <article
              key={projectId}
              className="works-lib-card canvas-project-card"
              role="link"
              aria-label={projectName}
              tabIndex={0}
              onClick={() => openCanvasProjectPage(projectId)}
              onKeyDown={(event) => openProjectFromKey(event, projectId)}
            >
              <div className={`works-lib-card__cover${coverUrl ? '' : ' works-lib-card__cover--placeholder'}`}>
                {coverUrl ? (
                  <img
                    className={`works-lib-card__cover-img${loadedCovers[projectId] ? ' is-loaded' : ''}`}
                    src={coverUrl}
                    alt=""
                    onLoad={() => setLoadedCovers((current) => current[projectId] ? current : { ...current, [projectId]: true })}
                  />
                ) : (
                  <img className="card-cover-placeholder-icon" src={emptyImageIconUrl} alt="" width={88} height={88} draggable={false} />
                )}
                {projectActions(project)}
              </div>
              <div className="works-lib-card__body">
                <div className="canvas-project-card__heading">
                  <h3 className="works-lib-card__title">
                    <StudioCanvasTooltip title={projectName}><span>{projectName}</span></StudioCanvasTooltip>
                  </h3>
                  {publicationCaption(project, true)}
                </div>
                <div className="works-lib-card__row">
                  <span className="works-lib-card__updated">最后更新 {formatCanvasUpdatedAt(project.updateTime)}</span>
                </div>
              </div>
            </article>
          )
        })}
    </div>
  )

  const recentCards = (
    <div className="home-recent">
      <button
        type="button"
        className="home-recent-card home-recent-card--new home-spot"
        disabled={creating}
        aria-busy={creating}
        onClick={() => void createAndEnter()}
      >
        <span className="home-recent-card__plus">{creating ? <LoadingOutlined spin /> : <HomeRecentPlusIcon />}</span>
        <b>{creating ? '正在创建…' : '新建画布'}</b>
      </button>
      {visibleItems.map((project) => {
        const projectId = projectIdOf(project)
        const projectName = project.projectName || '未命名画布'
        return (
          <article
            key={projectId}
            className="home-recent-card home-spot"
            role="link"
            tabIndex={0}
            onClick={() => openCanvasProjectPage(projectId)}
            onKeyDown={(event) => openProjectFromKey(event, projectId)}
          >
            <div className="home-recent-card__thumb">
              <HomeCanvasNodeThumb seedKey={projectId} image={project.coverUrl} />
            </div>
            {projectActions(project)}
            <div className="home-recent-card__meta">
              <StudioCanvasTooltip title={projectName}><b>{projectName}</b></StudioCanvasTooltip>
              {publicationCaption(project)}
              <small>{project.updateTime ? `编辑于 ${project.updateTime.slice(0, 16)}` : '继续创作'}</small>
            </div>
          </article>
        )
      })}
    </div>
  )

  return (
    <>
      <input
        ref={coverInputRef}
        className="canvas-project-cover-input"
        type="file"
        accept="image/*"
        aria-label="选择画布封面图片"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          void updateCover(file)
        }}
      />
      {variant === 'home' ? (
        enabled && !loading ? (
          <section className="home-recent-section" aria-label="最近画布">
            <div className="home-recent-section__heading">
              <h2>最近画布</h2>
              <button type="button" className="home-recent-section__more" onClick={() => router.push('/works?tab=canvas')}>
                查看全部 <ArrowRightOutlined aria-hidden="true" />
              </button>
            </div>
            {recentCards}
          </section>
        ) : null
      ) : (
        <section className="canvas-library-projects">
          {!searchOwnedByParent ? (
            <div className="canvas-library-projects__toolbar">
              {!onToolbarExtra && legacyCandidates.length > 0 ? <Button icon={<ImportOutlined />} onClick={() => setImportCandidate(legacyCandidates[0])}>导入本地画布</Button> : null}
              <Input.Search
                allowClear
                value={query}
                placeholder="搜索画布名称"
                onChange={(event) => { setPageNum(1); setQuery(event.target.value) }}
              />
            </div>
          ) : null}
          {libraryCards}
          {!loading && query.trim() && visibleItems.length === 0 ? (
            <p className="canvas-library-projects__note">没有匹配的画布</p>
          ) : null}
          {total > PAGE_SIZE ? <Pagination className="canvas-project-pagination" current={pageNum} pageSize={PAGE_SIZE} total={total} showSizeChanger={false} onChange={setPageNum} /> : null}
        </section>
      )}
      <Modal
        open={Boolean(renameProject)}
        title="重命名画布"
        rootClassName={`canvas-project-rename-modal canvas-project-rename-modal--${variant}`}
        className="create-flow-modal"
        okText="保存"
        cancelText="取消"
        okButtonProps={{ loading: Boolean(busyProjectId), disabled: !renameValue.trim() }}
        mask={{ closable: false }}
        onOk={() => void saveRename()}
        onCancel={() => { if (!busyProjectId) setRenameProject(null) }}
      >
        <Input
          autoFocus
          maxLength={100}
          showCount
          value={renameValue}
          aria-label="画布名称"
          onChange={(event) => setRenameValue(event.target.value)}
          onPressEnter={() => void saveRename()}
        />
      </Modal>
      <Modal
        open={Boolean(importCandidate)}
        title="导入本地自由画布"
        className="create-flow-modal"
        okText="导入并进入"
        okButtonProps={{ loading: importing, disabled: !importCandidate }}
        cancelButtonProps={{ disabled: importing }}
        closable={!importing}
        mask={{ closable: false }}
        onOk={() => void importLegacy()}
        onCancel={() => setImportCandidate(null)}
      >
        <p>选择此浏览器中保存的旧自由画布。导入会创建新项目，原本地数据会保留。</p>
        <Select
          className="canvas-project-import-select"
          aria-label="选择本地画布"
          value={importCandidate?.scopeKey || ''}
          options={legacyCandidates.map((candidate) => ({ value: candidate.scopeKey, label: `${candidate.title} · ${candidate.nodeCount} 个节点` }))}
          getPopupContainer={(trigger) => trigger.parentElement || document.body}
          onChange={(scopeKey) => {
            setImportCandidate(legacyCandidates.find((candidate) => candidate.scopeKey === scopeKey) || null)
          }}
        />
        {importCandidate ? <p className="canvas-project-import-note">
          将导入 {importCandidate.objectCount} 个对象。旧任务、个人资产、媒体临时地址及原工作区素材 ID 不会迁移；
          {importCandidate.omittedMediaCount > 0 ? ` ${importCandidate.omittedMediaCount} 个节点需要重新关联素材。` : '无需重新关联媒体。'}
          {importCandidate.repairedEntries > 0 ? ` 本地数据中有 ${importCandidate.repairedEntries} 项损坏记录，已从预览排除。` : ''}
        </p> : null}
      </Modal>
    </>
  )
}
