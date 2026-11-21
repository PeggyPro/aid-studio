'use client'

import { useState } from 'react'
import { PictureOutlined } from '@ant-design/icons'
import { buildRetinaDisplayImageUrl } from '~/utils/displayImageUrl'
import { ShimmerImage } from './ShimmerImage'
import './style-thumbnail.css'

export function StyleThumbnail({ url, width, name }: { url: string; width: number; name: string }) {
  const original = url.trim()
  const resized = buildRetinaDisplayImageUrl(original, width)
  const [failedSource, setFailedSource] = useState('')
  const source = failedSource === resized ? original : resized
  const canRetryOriginal = source !== original
  const placeholder = <div className="style-thumb-placeholder style-thumbnail__empty">
    <PictureOutlined aria-hidden="true" /><span>暂无预览</span>
  </div>

  if (!source) return placeholder
  return <ShimmerImage
    key={source}
    src={source}
    alt={name}
    wrapperClass="style-thumb-shimmer"
    imgClass="style-thumb-img"
    revealDirection="fade"
    loadTimeoutMs={8000}
    onError={canRetryOriginal ? () => setFailedSource(source) : undefined}
    errorSlot={placeholder}
  />
}
