import { VirtualCameraGuestRoute } from '@/components/studio/director/VirtualCameraGuestRoute'
import { Suspense } from 'react'
import type { Viewport } from 'next'
import '@/components/studio/director/virtual-camera-guest.css'

export const viewport: Viewport = { width: 'device-width', initialScale: 1, viewportFit: 'cover' }

export default function VirtualCameraPage() {
  return <Suspense fallback={<div role="status">正在加载远程机位…</div>}><VirtualCameraGuestRoute /></Suspense>
}
