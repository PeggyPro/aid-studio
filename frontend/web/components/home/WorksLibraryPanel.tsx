'use client'

import { ExportOutlined,SearchOutlined } from '@ant-design/icons'
import { message,Modal,Tooltip } from 'antd'
import { InfiniteScrollLoadFooter } from '~/components/common/InfiniteScrollLoadFooter'
import { PageLoadingOverlay } from '~/components/common/PageLoadingOverlay'
import { useRouter,useSearchParams } from 'next/navigation'
import type { CSSProperties,ReactNode,SyntheticEvent } from 'react'
import { useCallback,useEffect,useMemo,useRef,useState } from 'react'
import { WorksCardDeleteIcon } from '~/components/home/WorksCardActionIcons'
import { useEnterCreateFlowOverlay } from '~/composables/useEnterCreateFlowOverlay'
import { useInfiniteScrollPagination } from '~/composables/useInfiniteScrollPagination'
import { useCreationStore } from '~/stores/creation'
import type { CreationStep,Work } from '~/types'
import type { UserProjectRow } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import {
userEpisodeCreate,
userProjectDelete,
userProjectList,
userProjectUnpublish
} from '~/utils/businessApi'
import { API_DEFAULT_PAGE_SIZE } from '~/utils/business/shared'
import { buildOpenProjectFlowQuery,resolveCreateFlowEntryPath } from '~/utils/createFlowProjectContext'
import { CREATE_FLOW_STEP_ORDER,CREATE_SERIES_EPISODE_LIST_PATH } from '~/utils/createFlowRoutes'
import { emptyImageIconUrl } from '~/utils/emptyImageIcon'
import {
  buildWorksPageHref,
  isWorksProjectTab,
  resolveWorksPageTab,
  type WorksPageTab
} from '~/utils/worksPageTab'
import {
hydrateCreationStoreFromProjectDetail,
resetProjectDetailHydrateCache
} from '~/utils/hydrateCreationStoreFromProjectDetail'
import {
auditStatusBadgeLabel,
auditStatusBadgeTone,
isProjectPublicLockError,
isProjectPublished,
projectPublicLockUserHint,
type AuditBadgeTone
} from '~/utils/projectAudit'
import WorksLibraryAddCard from './WorksLibraryAddCard'
import DiscoverySubmissionsPanel from './DiscoverySubmissionsPanel'
import './WorksLibraryPanel.css'
import WorksLibraryPublishedAction from './WorksLibraryPublishedAction'
import { CanvasProjectCollection } from '@/components/canvas/CanvasProjectCollection'

type WorkCategory = WorksPageTab

const workCoverPlaceholderUrl = assetUrl(emptyImageIconUrl)
const WORKS_SCROLL_LOAD_THRESHOLD_PX = 240

export interface WorksLibraryPanelProps {
  /** 独立「我的作品」页：打开创建作品弹窗；携带当前列表 Tab 以预选电影/剧集 */
  onOpenCreate?: (tab: 'film' | 'series') => void
}

type WorkListItem = Work & {
  category: WorkCategory
  episodeCount?: number
  isPublished: boolean
  hasCover: boolean
  auditStatusLabel?: string | null
  auditStatusTone?: AuditBadgeTone | null
}

const typeTabs: { label: string; value: WorksPageTab }[] = [
  { label: '电影/短片', value: 'film' },
  { label: '电视剧集', value: 'series' },
  { label: '画布', value: 'canvas' },
  { label: '我的投稿', value: 'submission' }
]

function toWorkStatus(status?: number): Work['status'] {
  if (status === 4) return 'completed'
  if (status === 1 || status === 2 || status === 3) return 'in-progress'
  return 'draft'
}

function toCurrentStep(status?: number, currentStep?: number | null): CreationStep {
  const stepIndex = Number(currentStep) - 1
  if (Number.isInteger(stepIndex) && stepIndex >= 0 && stepIndex < CREATE_FLOW_STEP_ORDER.length) {
    return CREATE_FLOW_STEP_ORDER[stepIndex]
  }
  if (status === 4) return 'preview'
  if (status === 1 || status === 2 || status === 3) return 'dubbing'
  return 'global-setting'
}

function mapProjectToWorkItem(row: UserProjectRow): WorkListItem {
  const pt = String(row.projectType ?? '').toLowerCase()
  const category: WorkCategory = pt === 'series' ? 'series' : 'film'
  const coverRaw = (row.coverUrl || '').trim()
  const ec = row.episodeCount
  const episodeCount =
    typeof ec === 'number' && Number.isFinite(ec) ? Math.max(0, Math.floor(ec)) : 0
  return {
    id: String(row.id),
    title: row.projectName || '未命名草稿',
    description: row.projectDesc || '',
    coverImage: coverRaw || workCoverPlaceholderUrl,
    hasCover: !!coverRaw,
    author: { id: '0', username: '我', email: 'me@example.com', role: 'user' },
    createdAt: row.createTime || '',
    updatedAt: row.updateTime || row.createTime || '',
    status: toWorkStatus(row.status),
    views: 0,
    likes: 0,
    tags: [],
    currentStep: toCurrentStep(row.status, row.currentStep),
    category,
    episodeCount,
    isPublished: isProjectPublished(row.isPublic),
    auditStatusLabel: auditStatusBadgeLabel(row.status),
    auditStatusTone: auditStatusBadgeTone(row.status)
  }
}

function auditBadgeClass(tone?: AuditBadgeTone | null): string {
  if (tone === 'reviewing') return 'works-lib-card__cover-badge--reviewing'
  if (tone === 'failed') return 'works-lib-card__cover-badge--failed'
  return ''
}

function formatCompactNumber(n: number): string {
  if (n >= 10000) {
    return `${(n / 10000).toFixed(1)}万`
  }
  if (n >= 1000) {
    return `${(n / 1000).toFixed(1)}k`
  }
  return String(n)
}

const formatDate = (dateString: string) => {
  if (!dateString) return '--'
  const date = new Date(dateString)
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

function serializeFlowQuery(query: Record<string, string>): string {
  return new URLSearchParams(query).toString()
}

export function WorksLibraryPanel({ onOpenCreate }: WorksLibraryPanelProps) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { beginEnterCreateFlowOverlay, endEnterCreateFlowOverlay } = useEnterCreateFlowOverlay()

  const tabQuery = String(searchParams.get('tab') ?? '')
  const typeQuery = String(searchParams.get('type') ?? '')
  const searchQueryString = searchParams.toString()

  const [workType, setWorkType] = useState<WorkCategory>(() =>
    resolveWorksPageTab(tabQuery, typeQuery)
  )
  const [searchQuery, setSearchQuery] = useState('')
  const [canvasToolbarExtra, setCanvasToolbarExtra] = useState<ReactNode>(null)
  const setCanvasToolbarExtraStable = useCallback((node: ReactNode | null) => {
    setCanvasToolbarExtra(node)
  }, [])

  useEffect(() => {
    setWorkType(resolveWorksPageTab(tabQuery, typeQuery))
    setSearchQuery('')
  }, [tabQuery, typeQuery])

  function selectWorkType(tab: WorkCategory) {
    setSearchQuery('')
    setWorkType(tab)
    const href = buildWorksPageHref(searchQueryString, tab)
    router.replace(href, { scroll: false })
  }

  /** 列表刷新代际：封面 img 重建 + 缓存图补标 is-loaded */
  const [workListRevision, setWorkListRevision] = useState(0)

  const worksLibGridRef = useRef<HTMLDivElement | null>(null)

  /** 主封面图 decode 完成后再显示，切换 Tab 新列表在 fetch 成功后会清空 */
  const [coverLoadedById, setCoverLoadedById] = useState<Record<string, boolean>>({})

  function onWorkCoverLoad(workId: string) {
    setCoverLoadedById((prev) => ({ ...prev, [workId]: true }))
  }

  /** 浏览器缓存命中时 @load 可能早于监听绑定，需在 DOM 更新后补检 img.complete */
  const syncCoverLoadedFromCache = useCallback(() => {
    const root = worksLibGridRef.current
    if (!root) return
    const imgs = root.querySelectorAll<HTMLImageElement>(
      'img.works-lib-card__cover-img[data-work-id]'
    )
    if (!imgs.length) return
    setCoverLoadedById((prev) => {
      const next = { ...prev }
      let changed = false
      imgs.forEach((img) => {
        const workId = img.dataset.workId
        if (!workId || next[workId]) return
        if (img.complete && img.naturalWidth > 0) {
          next[workId] = true
          changed = true
        }
      })
      return changed ? next : prev
    })
  }, [])

  function onWorkCoverImgError(ev: SyntheticEvent<HTMLImageElement>, workId: string) {
    const el = ev.target as HTMLImageElement | null
    if (!el || el.dataset.coverFallback === '1') return
    el.dataset.coverFallback = '1'
    setMyWorks((prev) => {
      const idx = prev.findIndex((w) => w.id === workId)
      if (idx < 0) return prev
      const next = [...prev]
      next[idx] = {
        ...next[idx],
        hasCover: false,
        coverImage: workCoverPlaceholderUrl
      }
      return next
    })
  }

  /** 卡片添加分集进行中的作品 id */
  const [addingEpisodeProjectId, setAddingEpisodeProjectId] = useState<number | null>(null)

  const workTypeRef = useRef(workType)
  workTypeRef.current = workType
  const searchQueryRef = useRef(searchQuery)
  searchQueryRef.current = searchQuery

  const fetchWorkPage = useCallback(async (pageNum: number, pageSize: number) => {
    const { total, rows } = await userProjectList({
      projectType: workTypeRef.current === 'film' ? 'movie' : 'series',
      projectName: searchQueryRef.current.trim() || undefined,
      pageNum,
      pageSize
    })
    return {
      rows: rows.map(mapProjectToWorkItem),
      hasMore: pageNum * pageSize < total
    }
  }, [])

  const {
    items: myWorks,
    setItems: setMyWorks,
    loading: listRequestLoading,
    loadingMore: listLoadingMore,
    hasMore: listHasMore,
    initialLoaded: listInitialLoaded,
    appendTick: workListAppendTick,
    reload: fetchWorkList,
    setLoadMoreTrigger
  } = useInfiniteScrollPagination<WorkListItem>(worksLibGridRef, fetchWorkPage, {
    pageSize: API_DEFAULT_PAGE_SIZE,
    threshold: WORKS_SCROLL_LOAD_THRESHOLD_PX,
    onError: (_error, { reset }) => {
      message.error(reset ? '查询项目列表失败，请稍后重试' : '加载更多作品失败，请稍后重试')
    }
  })
  const listLoading = listRequestLoading || !listInitialLoaded
  const showListEnd = myWorks.length > API_DEFAULT_PAGE_SIZE

  const previousWorkCountRef = useRef(0)
  const [appendedFromIndex, setAppendedFromIndex] = useState<number | null>(null)
  useEffect(() => {
    if (workListAppendTick > 0) setAppendedFromIndex(previousWorkCountRef.current)
    previousWorkCountRef.current = myWorks.length
  }, [workListAppendTick, myWorks.length])

  useEffect(() => {
    if (listLoading) {
      previousWorkCountRef.current = 0
      setAppendedFromIndex(null)
      setCoverLoadedById({})
      setWorkListRevision((v) => v + 1)
      return
    }
    requestAnimationFrame(syncCoverLoadedFromCache)
  }, [listLoading, myWorks.length, syncCoverLoadedFromCache])

  async function addEpisodeFromCard(work: WorkListItem) {
    const projectId = Number(work.id)
    if (!Number.isFinite(projectId) || projectId <= 0) {
      message.error('项目ID无效')
      return
    }
    setAddingEpisodeProjectId(projectId)
    try {
      const nextNo = Math.max(0, Number(work.episodeCount) || 0) + 1
      await userEpisodeCreate({
        projectId,
        comicTitle: `第${nextNo}集`
      })
      message.success('已新增一集')
      await fetchWorkList()
    } catch (e: unknown) {
      if (isProjectPublicLockError(e)) {
        message.error(projectPublicLockUserHint())
        return
      }
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '新增失败')
    } finally {
      setAddingEpisodeProjectId(null)
    }
  }

  async function goToSeriesEpisodeManage(work: WorkListItem) {
    const projectId = Number(work.id)
    if (!Number.isFinite(projectId) || projectId <= 0) {
      message.error('项目ID无效')
      return
    }
    beginEnterCreateFlowOverlay()
    try {
      resetProjectDetailHydrateCache(projectId)
      const q = buildOpenProjectFlowQuery(projectId, {
        embedded: false,
        projectType: 'series'
      })
      // 先对齐路由再 hydrate，避免 store 已切、route 仍旧时把上一作品 loading 灌回
      router.push(`${CREATE_SERIES_EPISODE_LIST_PATH}?${serializeFlowQuery(q)}`)
      await hydrateCreationStoreFromProjectDetail(useCreationStore.getState(), projectId, {
        force: true
      })
      useCreationStore.getState().setSeriesFlowEnteredStoryScript(false)
    } catch {
      endEnterCreateFlowOverlay()
      message.error('进入剧集管理失败，请稍后重试')
    }
  }

  function renderWorkTitle(work: Work, className: string) {
    return (
      <h3 className={className} title={work.title}>
        <button type="button" className="works-lib-card__title-action" onClick={(event) => {
          event.stopPropagation()
          onWorkEditClick(work)
        }}>{work.title}</button>
      </h3>
    )
  }

  function onWorkEditClick(work: Work) {
    const item = work as WorkListItem
    if (item.category === 'series') {
      void goToSeriesEpisodeManage(item)
      return
    }
    void openWork(work)
  }


  const publishNavigatingProjectIdRef = useRef<number | null>(null)

  /**
   * 卡片右下角「发布至案例广场」：不再在列表内走发布/审核，
   * 而是跳转到作品当前进行到的流程步骤（已完成则为成品预览），
   * 由创作流程壳层根据 publishGuide 标记高亮引导：
   * 未完成 → 当前流程 tab；已完成 →「导出/发布」。
   */
  async function publishToCasePlazaFromCard(work: WorkListItem) {
    const projectId = Number(work.id)
    if (!Number.isFinite(projectId) || projectId <= 0) {
      message.error('项目ID无效')
      return
    }
    if (publishNavigatingProjectIdRef.current != null) return
    publishNavigatingProjectIdRef.current = projectId
    beginEnterCreateFlowOverlay()
    try {
      resetProjectDetailHydrateCache(projectId)
      const projectTypeGuess = work.category === 'series' ? 'series' : 'movie'
      const q = buildOpenProjectFlowQuery(projectId, {
        embedded: false,
        projectType: projectTypeGuess
      })
      const entryPath = await resolveCreateFlowEntryPath(projectId, projectTypeGuess)
      router.push(`${entryPath}?${serializeFlowQuery({ ...q, publishGuide: '1' })}`)
      await hydrateCreationStoreFromProjectDetail(useCreationStore.getState(), projectId, {
        force: true
      })
      const projectType = useCreationStore.getState().currentProjectType
      if (projectType && projectType !== projectTypeGuess) {
        const q2 = buildOpenProjectFlowQuery(projectId, {
          embedded: false,
          projectType
        })
        const entryPath2 = await resolveCreateFlowEntryPath(projectId, projectType)
        router.replace(`${entryPath2}?${serializeFlowQuery({ ...q2, publishGuide: '1' })}`)
      }
    } catch {
      endEnterCreateFlowOverlay()
      message.error('获取项目详情失败，请稍后重试')
    } finally {
      publishNavigatingProjectIdRef.current = null
    }
  }

  const [unpublishingProjectId, setUnpublishingProjectId] = useState<number | null>(null)
  const unpublishDialogProjectIdRef = useRef<number | null>(null)

  function cancelPublishWork(work: WorkListItem) {
    const projectId = Number(work.id)
    if (!Number.isFinite(projectId) || projectId <= 0) return
    if (unpublishingProjectId !== null || unpublishDialogProjectIdRef.current !== null) return
    unpublishDialogProjectIdRef.current = projectId
    Modal.confirm({
      title: '确认取消发布？',
      content: '取消后作品将从案例广场下架，您可继续修改内容。',
      okText: '取消发布',
      cancelText: '取消',
      onOk: async () => {
        setUnpublishingProjectId(projectId)
        try {
          await userProjectUnpublish({ id: projectId })
          message.success('已取消发布')
          await fetchWorkList()
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '取消发布失败')
          throw e
        } finally {
          setUnpublishingProjectId(null)
        }
      },
      afterClose: () => {
        unpublishDialogProjectIdRef.current = null
      }
    })
  }

  const filteredWorks = useMemo(() => {
    let list = myWorks.filter((w) => w.category === workType)
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase()
      list = list.filter(
        (w) => w.title.toLowerCase().includes(q) || w.description.toLowerCase().includes(q)
      )
    }
    return list
  }, [myWorks, workType, searchQuery])

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const statItems = useMemo(() => {
    const all = myWorks
    const total = all.length
    const views = all.reduce((s, w) => s + w.views, 0)
    const likes = all.reduce((s, w) => s + w.likes, 0)
    const inProgress = all.filter((w) => w.status === 'in-progress').length
    return [
      { key: 'total', label: '总作品', value: String(total) },
      { key: 'views', label: '总浏览', value: formatCompactNumber(views) },
      { key: 'likes', label: '获赞数', value: formatCompactNumber(likes) },
      { key: 'doing', label: '进行中', value: String(inProgress) }
    ]
  }, [myWorks])

  /** 单一入口：挂载时拉一次，切换 Tab 再拉；投稿 Tab 不走项目列表 */
  useEffect(() => {
    if (!isWorksProjectTab(workType)) return
    void fetchWorkList()
  }, [workType, fetchWorkList])

  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const prevSearchQueryRef = useRef(searchQuery)
  useEffect(() => {
    if (!isWorksProjectTab(workType)) return
    if (prevSearchQueryRef.current === searchQuery) return
    prevSearchQueryRef.current = searchQuery
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    searchTimerRef.current = setTimeout(() => {
      void fetchWorkList()
    }, 300)
  }, [searchQuery, fetchWorkList])

  useEffect(() => {
    return () => {
      if (searchTimerRef.current) {
        clearTimeout(searchTimerRef.current)
        searchTimerRef.current = null
      }
    }
  }, [])

  function goToCreate() {
    if (workType !== 'film' && workType !== 'series') return
    onOpenCreate?.(workType)
  }

  async function openWork(work: Work) {
    const projectId = Number(work.id)
    if (!Number.isFinite(projectId) || projectId <= 0) {
      message.error('项目ID无效')
      return
    }
    const item = work as WorkListItem
    const projectTypeGuess = item.category === 'series' ? 'series' : 'movie'
    beginEnterCreateFlowOverlay()
    try {
      resetProjectDetailHydrateCache(projectId)
      const q = buildOpenProjectFlowQuery(projectId, {
        embedded: false,
        projectType: projectTypeGuess
      })
      const entryPath = await resolveCreateFlowEntryPath(projectId, projectTypeGuess)
      // 先对齐路由再 hydrate，避免 store↔route 分歧窗口把旧作品 generating 灌进新作品
      router.push(`${entryPath}?${serializeFlowQuery(q)}`)
      await hydrateCreationStoreFromProjectDetail(useCreationStore.getState(), projectId, {
        force: true
      })
      const projectType = useCreationStore.getState().currentProjectType
      if (projectType && projectType !== projectTypeGuess) {
        const q2 = buildOpenProjectFlowQuery(projectId, {
          embedded: false,
          projectType
        })
        const entryPath2 = await resolveCreateFlowEntryPath(projectId, projectType)
        router.replace(`${entryPath2}?${serializeFlowQuery(q2)}`)
      }
    } catch (_error) {
      endEnterCreateFlowOverlay()
      message.error('获取项目详情失败，请稍后重试')
    }
  }

  function deleteWork(work: Work) {
    Modal.confirm({
      className: 'home-confirm-modal',
      wrapClassName: 'create-flow-modal home-confirm-wrap',
      centered: true,
      title: '删除作品',
      content: `确认删除《${work.title}》吗？`,
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      async onOk() {
        const projectId = Number(work.id)
        if (!Number.isFinite(projectId) || projectId <= 0) {
          message.error('项目ID无效')
          return
        }
        await userProjectDelete(projectId)
        message.success('删除成功')
        await fetchWorkList()
      }
    })
  }

  return (
    <div
      aria-busy={isWorksProjectTab(workType) && listLoading}
      className="works-page home-new-sub-page works-library-figma"
    >
      {isWorksProjectTab(workType) && listLoading && <PageLoadingOverlay label="正在加载我的作品…" />}
      <div className="page-content works-library-figma__inner">
        <header className="works-lib-header">
          <h1 className="works-lib-header__title">我的作品</h1>
        </header>

        {/* <section class="works-lib-stats" aria-label="作品统计">
          <div
            v-for="item in statItems"
            :key="item.key"
            class="works-lib-stat-card"
          >
            <span class="works-lib-stat-card__label">{{ item.label }}</span>
            <span class="works-lib-stat-card__value">{{ item.value }}</span>
          </div>
        </section> */}

        <section className="works-lib-toolbar" aria-label="筛选与搜索">
          <div className="works-lib-type-tabs" role="tablist">
            {typeTabs.map((tab) => (
              <button
                key={tab.value}
                type="button"
                role="tab"
                aria-selected={workType === tab.value}
                className={`works-lib-type-tabs__btn${workType === tab.value ? ' is-active' : ''}`}
                onClick={() => selectWorkType(tab.value)}
              >
                {tab.label}
              </button>
            ))}
          </div>
          {workType === 'canvas' ? canvasToolbarExtra : null}
          {workType !== 'submission' ? (
            <div className="works-lib-search">
              <SearchOutlined className="works-lib-search__ico" />
              <input
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                type="search"
                className="works-lib-search__input"
                placeholder={workType === 'canvas' ? '搜索画布名称' : '搜索作品...'}
                aria-label={workType === 'canvas' ? '搜索画布名称' : '搜索作品'}
                autoComplete="off"
              />
            </div>
          ) : null}
        </section>

        {workType === 'canvas' ? (
          <CanvasProjectCollection
            variant="library"
            query={searchQuery}
            onQueryChange={setSearchQuery}
            onToolbarExtra={setCanvasToolbarExtraStable}
          />
        ) : workType === 'submission' ? (
          <DiscoverySubmissionsPanel />
        ) : (
          <div ref={worksLibGridRef} className="works-lib-grid">
          <WorksLibraryAddCard label="新建作品" onClick={goToCreate} />
          {filteredWorks.map((work, workIdx) => (
            <article
              key={work.id}
              className={`works-lib-card${
                appendedFromIndex !== null && workIdx >= appendedFromIndex
                  ? ' works-lib-card--appended'
                  : ''
              }`}
              onClick={() => onWorkEditClick(work)}
              style={
                appendedFromIndex !== null && workIdx >= appendedFromIndex
                  ? ({
                      '--works-append-order': Math.min(workIdx - appendedFromIndex, 8)
                    } as CSSProperties)
                  : undefined
              }
            >
              <div
                className={`works-lib-card__cover${
                  !work.hasCover ? ' works-lib-card__cover--placeholder' : ''
                }`}
              >
                {!work.hasCover ? (
                  <img
                    className="card-cover-placeholder-icon"
                    src={workCoverPlaceholderUrl}
                    alt=""
                    width={88}
                    height={88}
                    draggable={false}
                  />
                ) : (
                  <img
                    key={`${workListRevision}-${work.id}`}
                    className={`works-lib-card__cover-img${
                      coverLoadedById[work.id] ? ' is-loaded' : ''
                    }`}
                    src={work.coverImage}
                    alt={work.title}
                    data-work-id={work.id}
                    loading="eager"
                    decoding="async"
                    onLoad={() => onWorkCoverLoad(work.id)}
                    onError={(ev) => onWorkCoverImgError(ev, work.id)}
                  />
                )}
                {/* 审核状态固定在左上角，卡片悬停时也保持可见。 */}
                {work.auditStatusLabel ? (
                  <span
                    className={`works-lib-card__cover-badge ${auditBadgeClass(
                      work.auditStatusTone
                    )}`.trimEnd()}
                  >
                    {work.auditStatusLabel}
                  </span>
                ) : null}
                <div className="works-lib-card__cover-actions">
                  <button
                    type="button"
                    className="works-lib-card__cover-btn"
                    aria-label="删除"
                    onClick={(e) => {
                      e.stopPropagation()
                      deleteWork(work)
                    }}
                  >
                    <WorksCardDeleteIcon />
                  </button>
                </div>
              </div>
              <div className="works-lib-card__body">
                <h3 className="works-lib-card__title" title={work.title}>
                  <button type="button" className="works-lib-card__title-action" onClick={(event) => {
                    event.stopPropagation()
                    onWorkEditClick(work)
                  }}>{work.title}</button>
                </h3>
                <div className="works-lib-card__row">
                  <span className="works-lib-card__updated">
                    最后更新 {formatDate(work.updatedAt)}
                  </span>
                  {work.category === 'series' ? (
                    <div className="works-lib-card__row-trailing">
                      <span className="works-lib-card__ep-label">
                        集数 <em>{work.episodeCount ?? 0}</em>
                      </span>
                      {work.isPublished ? (
                        <WorksLibraryPublishedAction
                          loading={unpublishingProjectId === Number(work.id)}
                          onCancel={() => cancelPublishWork(work)}
                        />
                      ) : (
                        <button
                          type="button"
                          className="works-lib-card__ep-add works-lib-card__ep-add--primary"
                          disabled={addingEpisodeProjectId === Number(work.id)}
                          aria-label="添加集"
                          onClick={(e) => {
                            e.stopPropagation()
                            void addEpisodeFromCard(work)
                          }}
                        >
                          +
                        </button>
                      )}
                    </div>
                  ) : work.isPublished ? (
                    <WorksLibraryPublishedAction
                      loading={unpublishingProjectId === Number(work.id)}
                      onCancel={() => cancelPublishWork(work)}
                    />
                  ) : (
                    <Tooltip
                      title="发布至案例广场"
                      placement="top"
                      classNames={{ root: 'works-lib-publish-tooltip' }}
                    >
                      <button
                        type="button"
                        className="works-lib-card__open"
                        aria-label="发布至案例广场"
                        onClick={(e) => {
                          e.stopPropagation()
                          void publishToCasePlazaFromCard(work)
                        }}
                      >
                        <ExportOutlined />
                      </button>
                    </Tooltip>
                  )}
                </div>
              </div>
            </article>
          ))}
          {listHasMore || listLoadingMore || showListEnd ? (
            <div ref={setLoadMoreTrigger} className="works-lib-list-footer">
              <InfiniteScrollLoadFooter
                loading={listLoadingMore}
                hasMore={listHasMore}
                hasItems={showListEnd}
                loadingText="正在加载更多作品…"
                endText="已加载全部作品"
              />
            </div>
          ) : null}
        </div>
        )}
      </div>
    </div>
  )
}

export default WorksLibraryPanel
