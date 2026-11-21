'use client'

import { BellOutlined, CheckOutlined, DownOutlined, MailOutlined } from '@ant-design/icons'
import { Modal } from 'antd'
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { StudioCanvasCollapse } from '@/components/studio/canvas/StudioCanvasCollapse'
import type { StudioNotificationClient, StudioNotificationView } from '@/utils/studio/studioNotificationClient'
import { markdownToAssistantConversationHtml } from '@/utils/htmlPlain'
import './studio-notifications.css'

function MessagePlaceholder({ icon, title }: { icon: ReactNode; title: string }) {
  return <div className="studio-notifications__empty" role="status">
    <span aria-hidden="true">{icon}</span>
    <strong>{title}</strong>
  </div>
}

function NotificationBody({ id, children }: { id: string; children: ReactNode }) {
  const host = useRef<HTMLDivElement>(null)
  const [retained, setRetained] = useState(children)
  const open = children !== null

  // Keep the outgoing body mounted until the shared collapse finishes shrinking.
  if (open && retained !== children) setRetained(children)
  useEffect(() => {
    if (open || retained === null) return
    let current = true
    const animations = host.current?.getAnimations({ subtree: true }) ?? []
    void Promise.allSettled(animations.map(animation => animation.finished)).then(() => {
      if (current) setRetained(null)
    })
    return () => { current = false }
  }, [open, retained])

  return <div ref={host}>
    <StudioCanvasCollapse id={id} open={open}>
      <div className="studio-notifications__body">{children ?? retained}</div>
    </StudioCanvasCollapse>
  </div>
}

export function StudioNotificationModal({ client, state }: { client: StudioNotificationClient; state: StudioNotificationView }) {
  const list = useRef<HTMLElement>(null)
  const end = useRef<HTMLDivElement>(null)
  const bodyId = useId()
  const detail = state.detail?.id === state.selectedId ? state.detail : null
  const selectedRowVisible = state.rows.some(row => row.id === state.selectedId)
  const more = state.pageNum * state.pageSize < state.total
  const html = useMemo(() => detail?.contentFormat === 'MARKDOWN' ? markdownToAssistantConversationHtml(detail.content) : '', [detail])

  useEffect(() => {
    if (state.open && selectedRowVisible && detail && !state.detailLoading && !state.detailError) {
      void client.markDisplayed(detail.id)
    }
  }, [client, state.open, selectedRowVisible, detail, state.detailLoading, state.detailError])

  useEffect(() => {
    if (state.open && state.selectedId && !state.loading && !state.error && !selectedRowVisible) client.clearSelection()
  }, [client, state.open, state.selectedId, state.loading, state.error, selectedRowVisible])

  useEffect(() => {
    if (!state.open || !more || state.loading || state.error || state.updated || !list.current || !end.current) return
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) client.loadMore()
    }, { root: list.current, rootMargin: '0px 0px 60px' })
    observer.observe(end.current)
    return () => observer.disconnect()
  }, [client, more, state.open, state.loading, state.error, state.updated, state.pageNum, state.selectedId])

  useEffect(() => { list.current?.scrollTo({ top: 0 }) }, [state.open, state.unreadOnly])

  return (
    <Modal open={state.open} onCancel={client.close} footer={null} centered width={880}
      styles={{ container: { padding: 0 }, header: { margin: 0 }, body: { padding: 0 } }}
      className="studio-notification-modal" title={
        <div className="studio-notification-title">
          <strong>消息通知</strong><small>官方通知、产品动态与账户消息</small>
        </div>
      }>
      <div className="studio-notifications">
        <aside className="studio-notifications__sidebar" aria-label="消息筛选">
          <div className="studio-notifications__channel">
            <MailOutlined aria-hidden="true" />
            <strong>官方通知</strong>
            {state.unreadCount > 0 && (
              <span className="app-notification-badge" role="img" aria-label={`${state.unreadCount} 条未读通知`}>
                {state.unreadCount > 99 ? '99+' : state.unreadCount}
              </span>
            )}
          </div>
          <div className="studio-notifications__filters" role="group" aria-label="阅读状态筛选">
            <button type="button" aria-pressed={!state.unreadOnly} onClick={() => client.setUnreadOnly(false)}>全部</button>
            <button type="button" aria-pressed={state.unreadOnly} onClick={() => client.setUnreadOnly(true)}>未读</button>
          </div>
          <button type="button" className="studio-notifications__read-all" disabled={!state.unreadCount || state.readAllBusy}
            onClick={() => void client.readAll()}>
            <CheckOutlined aria-hidden="true" /> 全部已读
          </button>
        </aside>

        <section ref={list} className="studio-notifications__list" aria-label="消息列表" tabIndex={0} aria-busy={state.loading}>
          {state.rows.map(row => {
            const expanded = state.selectedId === row.id
            const loading = expanded && state.detailLoading
            const rowBodyId = `${bodyId}-${row.id}`
            return <article key={row.id} className="studio-notifications__card">
              <button type="button" className="studio-notifications__row" aria-expanded={expanded} aria-busy={loading}
                aria-controls={rowBodyId} onClick={() => expanded ? client.clearSelection() : void client.select(row.id)}>
                <span className="studio-notifications__row-title">
                  {!row.isRead && <i role="img" aria-label="未读" />}
                  <strong>{row.title}</strong>
                </span>
                <span className="studio-notifications__disclosure">
                  {expanded ? '收起' : '展开'} <DownOutlined aria-hidden="true" />
                </span>
                <time>{row.publishTime}</time>
                {(row.summary || loading) && <span className="studio-notifications__preview" aria-hidden={expanded && !loading}>
                  <span className="studio-notifications__summary">{loading ? '正在读取正文…' : row.summary}</span>
                </span>}
              </button>
              <NotificationBody id={rowBodyId}>
                {expanded && (detail || state.detailError) ? <>
                  {detail?.contentFormat === 'MARKDOWN' ? (
                    <div className="studio-notifications__markdown" dangerouslySetInnerHTML={{ __html: html }} />
                  ) : detail ? <p className="studio-notifications__plain">{detail.content}</p> : null}
                  {state.detailError && <div role="alert" className="studio-notifications__error">{state.detailError}
                    <button type="button" onClick={() => {
                      if (detail) void client.markDisplayed(detail.id)
                      else void client.select(row.id)
                    }}>重试</button>
                  </div>}
                </> : null}
              </NotificationBody>
            </article>
          })}
          {!state.rows.length && !state.loading && !state.error && (
            <MessagePlaceholder icon={<BellOutlined />} title={state.unreadOnly ? '没有未读通知' : '暂无通知'} />
          )}
          {state.error && <div role="alert" className="studio-notifications__error">{state.error}
            <button type="button" onClick={client.retryList}>重试</button>
          </div>}
          {state.loading && <p className="studio-notifications__loading" role="status">正在读取消息…</p>}
          <div ref={end} className="studio-notifications__end" aria-hidden="true" />
        </section>
      </div>
    </Modal>
  )
}
