'use client'

/* eslint-disable @next/next/no-img-element -- OSS 动态媒体地址无法使用静态 image loader。 */

import { Empty, Modal, Spin, Tooltip, message } from 'antd'
import type { CSSProperties } from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'
import deleteWhiteIcon from '~/assets/img/home/delete-white.svg'
import editWhiteIcon from '~/assets/img/home/edit-white.svg'
import { InfiniteScrollLoadFooter } from '~/components/common/InfiniteScrollLoadFooter'
import { useInfiniteScrollPagination } from '~/composables/useInfiniteScrollPagination'
import type { DiscoveryReviewStatus, UserDiscoveryItem } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { userDiscoveryDelete, userDiscoveryMyPage } from '~/utils/businessApi'
import { API_DEFAULT_PAGE_SIZE } from '~/utils/business/shared'
import {
  canResubmitDiscovery,
  DISCOVERY_REVIEW_STATUS_META
} from '~/utils/discoveryReviewStatus'
import { emptyImageIconUrl } from '~/utils/emptyImageIcon'
import DiscoverySubmissionModal from './DiscoverySubmissionModal'
import './DiscoverySubmissionsPanel.css'

const deleteWhiteUrl = assetUrl(deleteWhiteIcon)
const editWhiteUrl = assetUrl(editWhiteIcon)
const coverPlaceholderUrl = assetUrl(emptyImageIconUrl)

function errorText(error: unknown, fallback: string) {
  const value = error as { msg?: string; message?: string }
  return String(value?.msg || value?.message || fallback)
}

function discoveryReviewBadgeClass(status: DiscoveryReviewStatus): string {
  if (status === 'PENDING') return 'works-lib-card__cover-badge--reviewing'
  if (status === 'APPROVED') return 'works-lib-card__cover-badge--passed'
  return 'works-lib-card__cover-badge--failed'
}

export default function DiscoverySubmissionsPanel() {
  const gridRef = useRef<HTMLDivElement | null>(null)
  const [coverLoadedById, setCoverLoadedById] = useState<Record<number, boolean>>({})

  const [modalOpen, setModalOpen] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)

  const fetchSubmissionPage = useCallback(async (pageNum: number, pageSize: number) => {
    const result = await userDiscoveryMyPage({ pageNum, pageSize })
    return {
      rows: result.data,
      total: result.total,
      hasMore: pageNum * pageSize < result.total
    }
  }, [])

  const {
    items,
    total,
    loading,
    loadingMore,
    initialLoaded,
    loadError,
    hasMore,
    appendTick,
    reload,
    setLoadMoreTrigger
  } = useInfiniteScrollPagination<UserDiscoveryItem>(gridRef, fetchSubmissionPage, {
    pageSize: API_DEFAULT_PAGE_SIZE,
    onError: (_error, { reset }) => {
      message.error(reset ? '投稿列表加载失败，请重试' : '加载更多投稿失败，请重试')
    }
  })

  const previousItemCountRef = useRef(0)
  const [appendedFromIndex, setAppendedFromIndex] = useState<number | null>(null)

  useEffect(() => {
    if (appendTick > 0) setAppendedFromIndex(previousItemCountRef.current)
    previousItemCountRef.current = items.length
  }, [appendTick, items.length])

  useEffect(() => {
    void reload()
  }, [reload])

  useEffect(() => {
    if (loading) {
      previousItemCountRef.current = 0
      setAppendedFromIndex(null)
      setCoverLoadedById({})
    }
  }, [loading])

  function openEditModal(item: UserDiscoveryItem) {
    if (!canResubmitDiscovery(item.reviewStatus)) return
    setEditingId(item.id)
    setModalOpen(true)
  }

  function confirmDelete(item: UserDiscoveryItem) {
    Modal.confirm({
      className: 'home-confirm-modal',
      wrapClassName: 'create-flow-modal home-confirm-wrap',
      centered: true,
      title: '删除投稿',
      content: `确定删除「${item.name}」吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        try {
          await userDiscoveryDelete({ id: item.id })
          message.success('投稿已删除')
          await reload()
        } catch (err) {
          message.error(errorText(err, '删除失败，请重试'))
          return Promise.reject(err)
        }
      }
    })
  }

  return (
    <div className="discovery-submissions-panel" aria-live="polite">
      <div className="discovery-submissions-panel__toolbar">
        <span>共 {total} 条投稿</span>
      </div>

      {loading || !initialLoaded ? (
        <div className="discovery-submissions-panel__state">
          <Spin />
          <span>正在加载投稿</span>
        </div>
      ) : loadError && items.length === 0 ? (
        <div className="discovery-submissions-panel__state discovery-submissions-panel__state--error">
          <p>投稿列表加载失败，请重试</p>
          <button
            type="button"
            className="works-lib-empty__btn"
            onClick={() => void reload()}
          >
            重试
          </button>
        </div>
      ) : items.length === 0 ? (
        <Empty description="暂无投稿" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <div ref={gridRef} className="works-lib-grid discovery-submissions-panel__grid">
          {items.map((item, itemIndex) => {
            const status = DISCOVERY_REVIEW_STATUS_META[item.reviewStatus]
            const canEdit = canResubmitDiscovery(item.reviewStatus)
            const hasCover = Boolean(String(item.coverUrl || '').trim())
            const timeLabel = item.reviewTime
              ? `审核时间 ${item.reviewTime}`
              : `投稿时间 ${item.createTime || '-'}`

            return (
              <article
                key={item.id}
                className={`works-lib-card${
                  appendedFromIndex !== null && itemIndex >= appendedFromIndex
                    ? ' works-lib-card--appended'
                    : ''
                }`}
                style={
                  appendedFromIndex !== null && itemIndex >= appendedFromIndex
                    ? ({
                        '--works-append-order': Math.min(itemIndex - appendedFromIndex, 8)
                      } as CSSProperties)
                    : undefined
                }
              >
                <div
                  className={`works-lib-card__cover${
                    hasCover ? '' : ' works-lib-card__cover--placeholder'
                  }`}
                >
                  {!hasCover ? (
                    <img
                      className="card-cover-placeholder-icon"
                      src={coverPlaceholderUrl}
                      alt=""
                      width={88}
                      height={88}
                      draggable={false}
                    />
                  ) : (
                    <img
                      className={`works-lib-card__cover-img${
                        coverLoadedById[item.id] ? ' is-loaded' : ''
                      }`}
                      src={item.coverUrl}
                      alt={item.name}
                      loading="lazy"
                      decoding="async"
                      onLoad={() =>
                        setCoverLoadedById((prev) => ({ ...prev, [item.id]: true }))
                      }
                      onError={() =>
                        setCoverLoadedById((prev) => ({ ...prev, [item.id]: true }))
                      }
                    />
                  )}
                  <span
                    className={`works-lib-card__cover-badge ${discoveryReviewBadgeClass(
                      item.reviewStatus
                    )}`.trimEnd()}
                    title={item.reviewReason || undefined}
                  >
                    {status.text}
                  </span>
                  <div className="works-lib-card__cover-actions">
                    <button
                      type="button"
                      className="works-lib-card__cover-btn"
                      aria-label={`删除投稿：${item.name}`}
                      onClick={(e) => {
                        e.stopPropagation()
                        confirmDelete(item)
                      }}
                    >
                      <img src={deleteWhiteUrl} alt="" />
                    </button>
                    {canEdit ? (
                      <button
                        type="button"
                        className="works-lib-card__cover-btn"
                        aria-label={`编辑投稿：${item.name}`}
                        onClick={(e) => {
                          e.stopPropagation()
                          openEditModal(item)
                        }}
                      >
                        <img src={editWhiteUrl} alt="" />
                      </button>
                    ) : null}
                  </div>
                </div>
                <div className="works-lib-card__body">
                  <h3 className="works-lib-card__title" title={item.name}>
                    {item.name}
                  </h3>
                  <div className="works-lib-card__row">
                    <Tooltip
                      title={
                        item.reviewReason
                          ? `审核说明：${item.reviewReason}`
                          : item.description || undefined
                      }
                    >
                      <span className="works-lib-card__updated">{timeLabel}</span>
                    </Tooltip>
                  </div>
                </div>
              </article>
            )
          })}
          <div ref={setLoadMoreTrigger} className="works-lib-list-footer">
            <InfiniteScrollLoadFooter
              loading={loadingMore}
              hasMore={hasMore}
              hasItems
              loadingText="正在加载更多投稿…"
              endText="已加载全部投稿"
            />
          </div>
        </div>
      )}

      <DiscoverySubmissionModal
        open={modalOpen}
        editingId={editingId}
        onOpenChange={setModalOpen}
        onSuccess={() => void reload()}
      />
    </div>
  )
}
