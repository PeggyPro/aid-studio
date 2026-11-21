'use client'

import { GlobalOutlined, LinkOutlined, ShareAltOutlined } from '@ant-design/icons'
import { message } from 'antd'
import { lazy, Suspense, useState } from 'react'
import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { apiErrorMessage } from '@/utils/apiError'
import { withAppBasePath } from '@/utils/appBasePath'
import { canvasShareCreate, canvasShareList } from '@/utils/business/canvas'
import { canvasAccessUncertaintyStore, createCanvasAccessLinkSafely } from '@/utils/canvas/canvasAccessCreation'
import { copyPlainText } from '@/utils/clipboard'
import './studio-publish-share.css'

const StudioCanvasPublicationModal = lazy(() => import('./StudioCanvasPublicationModal'))

/** 与协作面板「创建分享」的默认有效期一致。 */
const CANVAS_SHARE_VALID_DAYS = 7

export function StudioCanvasPublishShare({
  canvasRuntime
}: {
  canvasRuntime?: CanvasProjectWorkspaceRuntime | null
}) {
  const [open, setOpen] = useState(false)
  const [publishOpen, setPublishOpen] = useState(false)
  const [sharing, setSharing] = useState(false)
  const canvasProjectId = canvasRuntime?.project ? String(canvasRuntime.project.projectId) : ''

  const publish = () => {
    setOpen(false)
    if (!canvasProjectId) {
      message.warning('画布还没有保存为项目，暂时不能发布')
      return
    }
    if (canvasRuntime?.role !== 'OWNER') {
      message.warning('只有画布所有者可以发布')
      return
    }
    setPublishOpen(true)
  }

  const share = async () => {
    if (sharing) return
    if (!canvasProjectId) {
      message.warning('画布还没有保存为项目，暂时不能分享')
      return
    }
    if (canvasRuntime?.role !== 'OWNER') {
      message.warning('只有画布所有者可以分享')
      return
    }
    if (canvasAccessUncertaintyStore.read(canvasProjectId, 'share')) {
      message.warning('上次分享可能已创建，请先在协作面板核对后再创建')
      return
    }
    setSharing(true)
    try {
      const result = await createCanvasAccessLinkSafely(
        async () => (await canvasShareList(canvasProjectId)).items || [],
        () => canvasShareCreate({ projectId: canvasProjectId, validDays: CANVAS_SHARE_VALID_DAYS })
      )
      if (result.status === 'created') {
        const token = result.value.shareToken || ''
        if (!token) {
          message.error('分享已创建，但没有返回可复制的链接')
          return
        }
        const link = `${window.location.origin}${withAppBasePath('/canvas/share')}#token=${encodeURIComponent(token)}`
        const copied = await copyPlainText(link)
        if (!copied) {
          message.error('分享链接已生成，但复制失败，请稍后重试')
          return
        }
        setOpen(false)
        message.success('分享链接已复制')
        return
      }
      if (result.status === 'uncertain') {
        canvasAccessUncertaintyStore.write(canvasProjectId, 'share', {
          beforeIds: result.beforeIds,
          newActiveIds: result.newActiveIds
        })
        message.warning('分享创建结果不确定，请到协作面板核对后再创建')
        return
      }
      message.error(apiErrorMessage(result.error, '创建分享失败'))
    } finally {
      setSharing(false)
    }
  }

  const panel = (
    <section className="studio-publish-share" aria-label="发布与分享">
      <h2>发布与分享</h2>
      <button type="button" className="studio-publish-share__action" onClick={publish}>
        <span className="studio-publish-share__icon" aria-hidden><GlobalOutlined /></span>
        <span>
          <strong>在案例广场发布</strong>
          <small>发布你的作品和创作过程，让更多创作者看到。</small>
        </span>
      </button>
      <button type="button" className="studio-publish-share__action" disabled={sharing} onClick={() => void share()}>
        <span className="studio-publish-share__icon" aria-hidden><LinkOutlined /></span>
        <span>
          <strong>分享链接</strong>
          <small>拥有此链接的人可以查看并复制你的画布。</small>
        </span>
      </button>
    </section>
  )

  return (
    <>
      <StudioCanvasTooltip title="发布与分享" open={open ? false : undefined}>
        <StudioCanvasPopover
          open={open}
          onOpenChange={setOpen}
          trigger="click"
          placement="bottomRight"
          arrow={false}
          content={panel}
          classNames={{ root: 'studio-publish-share-popover' }}
        >
          <button
            type="button"
            className="studio-topbar__icon-trigger"
            aria-label="发布与分享"
            aria-haspopup="dialog"
            aria-expanded={open}
          >
            <ShareAltOutlined />
          </button>
        </StudioCanvasPopover>
      </StudioCanvasTooltip>
      <Suspense fallback={null}>
        {canvasRuntime && <StudioCanvasPublicationModal
          open={publishOpen}
          runtime={canvasRuntime}
          onOpenChange={setPublishOpen}
          onSuccess={() => { void canvasRuntime?.refreshProject() }}
        />}
      </Suspense>
    </>
  )
}
