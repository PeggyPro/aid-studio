'use client'

import { useState } from 'react'
import { Button, Empty, Input, Space } from 'antd'
import Link from 'next/link'
import CasePlazaMasonry from '@/components/home/CasePlazaMasonry'
import { useCanvasPublicationChannel } from '@/hooks/useCanvasPublicationChannel'
import { useCanvasPublicationFeed } from '@/hooks/useCanvasPublicationFeed'
import { useCreateDefaultCanvasProject } from '@/hooks/useCreateDefaultCanvasProject'
import { openLoginModal } from '@/stores/loginModal'
import { useUserStore } from '@/stores/user'
import './canvas-publications.css'

export function CanvasPublicationGallery() {
  const enabled = useCanvasPublicationChannel()
  const signedIn = useUserStore((state) => Boolean(state.token))
  const { createAndEnter, creating } = useCreateDefaultCanvasProject()

  return <CanvasPublicationGalleryContent enabled={enabled} actions={
    <Space wrap>
      <Link href="/works/?tab=canvas">我的画布</Link>
      <Button type="primary" loading={creating} onClick={() => {
        if (signedIn) void createAndEnter()
        else openLoginModal({ redirect: '/canvas/' })
      }}>新建画布</Button>
    </Space>
  } />
}

export function CanvasPublicationGalleryContent({ enabled, embedded = false, searchQuery, actions }: {
  enabled: boolean | null
  embedded?: boolean
  searchQuery?: string
  actions?: React.ReactNode
}) {
  const [localQuery, setLocalQuery] = useState('')
  const query = searchQuery ?? localQuery
  const feed = useCanvasPublicationFeed(enabled === true, query)

  return <section className={`canvas-publications${embedded ? ' canvas-publications--embedded' : ''}`} aria-label="公开画布作品">
    {!embedded && <header>
      <div><h1>公开画布</h1><p>探索创作者公开的作品和制作过程</p>{actions}</div>
      <Input.Search value={query} onChange={(event) => setLocalQuery(event.target.value)}
        placeholder="搜索画布作品" aria-label="搜索画布作品" maxLength={100} allowClear />
    </header>}
    {enabled === false ? <Empty description="画布频道暂未开放" /> : <CasePlazaMasonry
      appearance={embedded ? 'home-v4' : 'default'} items={feed.items} status={feed.status} error={feed.error} hasMore={feed.hasMore}
      onLoadMore={feed.loadMore} onReload={feed.reload} />}
  </section>
}
