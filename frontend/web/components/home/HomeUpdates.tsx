'use client'

import { NotificationOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { useHomeAnnouncementBanners } from '~/hooks/useHomeAnnouncementBanners'
import { useUserStore } from '~/stores/user'
import HomeUpdatesDialog from './HomeUpdatesDialog'

export default function HomeUpdates() {
  const token = useUserStore((state) => state.token)
  return token ? <AccountHomeUpdates key={token} /> : null
}

function AccountHomeUpdates() {
  const { latest } = useHomeAnnouncementBanners()
  const [open, setOpen] = useState(false)

  return (
    <>
      <button type="button" className="home-updates-trigger" onClick={() => setOpen(true)}>
        <NotificationOutlined aria-hidden /> 最新公告
      </button>
      {open && <HomeUpdatesDialog item={latest} onDismiss={() => setOpen(false)} />}
    </>
  )
}
