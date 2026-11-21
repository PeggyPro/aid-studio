'use client'

import WorksLibraryPanel from '~/components/home/WorksLibraryPanel'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { openLoginModal } from '@/stores/loginModal'
import { useUserStore } from '~/stores/user'

/** 原 pages/works.vue：layout=home-new，由 app/(home)/layout.tsx 承担壳层。 */
export default function WorksPage() {
  const token = useUserStore((s) => s.token)

  const isLoggedIn = !!token
  const homeCreateModal = useHomeShellCreateModal()

  function goLogin() {
    openLoginModal({ redirect: '/works' })
  }

  function onOpenCreate(tab: 'film' | 'series') {
    if (!isLoggedIn) {
      goLogin()
      return
    }
    homeCreateModal.openCreateModal({ worksTab: tab, postCreateDestination: 'choose' })
  }

  return <WorksLibraryPanel onOpenCreate={onOpenCreate} />
}
