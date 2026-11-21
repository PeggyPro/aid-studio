'use client'

/* eslint-disable @next/next/no-img-element -- 服务商 LOGO 来自模型列表接口 URL。 */
import { ApiOutlined } from '@ant-design/icons'
import { useState } from 'react'

export function StudioModelProviderIcon({
  logo,
  name
}: {
  logo?: string | null
  name?: string
}) {
  const src = String(logo || '').trim()
  const [failedSrc, setFailedSrc] = useState('')
  if (!src || failedSrc === src) return <ApiOutlined aria-hidden="true" />
  return <img src={src} alt="" aria-hidden="true" title={name} onError={() => setFailedSrc(src)} />
}
