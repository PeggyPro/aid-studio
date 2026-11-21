'use client'

import { ArrowRightOutlined, CloseOutlined } from '@ant-design/icons'
import Image from 'next/image'
import Link from 'next/link'
import { useEffect, useId, useRef, useState } from 'react'
import { isHomeCanvasLaunchAnnouncement, type HomeAnnouncementItem } from '~/utils/homeAnnouncementBanners'
import HomeCanvasTour from './HomeCanvasTour'
import './HomeUpdatesDialog.css'

function AnnouncementPage({ item, titleId }: { item: HomeAnnouncementItem; titleId: string }) {
  const [mediaFailed, setMediaFailed] = useState(false)
  const [coverFailed, setCoverFailed] = useState(false)
  const { media } = item
  const video = media?.type === 'video' && !mediaFailed
  const image = media && !coverFailed && (mediaFailed ? media.cover : media.url)

  return (
    <article className="home-updates-announcement">
      {video || image ? (
        <div className="home-updates-media">
          {video ? (
            <video
              controls
              playsInline
              preload="metadata"
              poster={media.cover || undefined}
              src={media.url}
              aria-label={item.title}
              onError={() => setMediaFailed(true)}
            />
          ) : image ? (
            <Image
              fill
              unoptimized
              sizes="(max-width: 740px) 100vw, 680px"
              src={image}
              alt={item.title}
              onError={() => mediaFailed ? setCoverFailed(true) : setMediaFailed(true)}
            />
          ) : null}
        </div>
      ) : null}
      <div className="home-updates-copy">
        <p className="home-updates-kicker">官方公告</p>
        <h2 id={titleId}>{item.title}</h2>
        {item.summary ? <p className="home-updates-summary">{item.summary}</p> : null}
      </div>
    </article>
  )
}

interface HomeUpdatesDialogProps {
  item: HomeAnnouncementItem | null
  onDismiss: () => void
}

export default function HomeUpdatesDialog({ item, onDismiss }: HomeUpdatesDialogProps) {
  const dialog = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const showCanvasTour = item != null && isHomeCanvasLaunchAnnouncement(item)

  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])

  function close() {
    // Close while the dialog is still attached so the browser can restore focus.
    dialog.current?.close()
    onDismiss()
  }

  const link = item?.link
  return (
    <dialog
      ref={dialog}
      className={`home-updates-dialog${showCanvasTour ? ' home-updates-dialog--tour' : ''}`}
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault()
        close()
      }}
    >
      <header className="home-updates-header">
        {showCanvasTour ? (
          <div className="home-updates-tour-heading">
            <div>
              <p>产品更新</p>
              <h2 id={titleId}>{item.title}</h2>
            </div>
          </div>
        ) : <div><span className="home-updates-mark">AID</span> 最新公告</div>}
        <button type="button" aria-label="关闭弹窗" className="home-updates-icon" onClick={close}>
          <CloseOutlined aria-hidden />
        </button>
      </header>

      <div className="home-updates-body">
        {showCanvasTour ? (
          <HomeCanvasTour key={item.id} />
        ) : item ? (
          <AnnouncementPage key={item.id} item={item} titleId={titleId} />
        ) : (
          <div className="home-updates-copy"><h2 id={titleId}>暂无公告</h2></div>
        )}
      </div>

      <footer className="home-updates-footer">
        {link ? (
          <Link
            className="home-updates-primary"
            href={link.href}
            target={link.external ? '_blank' : undefined}
            rel={link.external ? 'noopener noreferrer' : undefined}
            onClick={close}
          >
            查看详情 <ArrowRightOutlined aria-hidden />
          </Link>
        ) : (
          <button type="button" className="home-updates-primary" onClick={close}>
            {showCanvasTour ? '完成' : '我知道了'}
          </button>
        )}
      </footer>
    </dialog>
  )
}
