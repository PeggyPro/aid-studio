'use client'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { AppstoreOutlined } from '@ant-design/icons'
import { useCanvasPublicationChannel } from '@/hooks/useCanvasPublicationChannel'

export function CanvasPublicationNav({
  className = 'home-new-nav-item',
}: {
  className?: string
}) {
  const enabled = useCanvasPublicationChannel(),
    path = usePathname()
  if (!enabled) return null
  return (
    <Link
      href="/canvas"
      className={`${className}${path === '/canvas' ? ' is-active' : ''}`}
      aria-current={path === '/canvas' ? 'page' : undefined}
    >
      <AppstoreOutlined aria-hidden />
      <span>画布</span>
    </Link>
  )
}
