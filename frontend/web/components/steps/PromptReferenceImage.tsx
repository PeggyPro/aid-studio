'use client'

import { useEffect, useState } from 'react'
import { PictureOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { buildUserApiAuthHeaders, resolveClientApiUrl } from '~/utils/api'
import { createTrackedObjectUrl, revokeObjectUrl } from '~/utils/objectUrl'

/** Legacy official covers live behind the same first-party gateway as the dictionary. */
export function PromptReferenceImage({ src, label }: { src: string; label: string }) {
  const legacy = src.startsWith('/aid/storyboard/img/')
  const [image, setImage] = useState(legacy ? '' : src)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    if (!legacy) return
    const controller = new AbortController()
    let objectUrl = ''
    const timeout = window.setTimeout(() => { controller.abort(); setFailed(true) }, 12000)
    void fetch(resolveClientApiUrl(src.slice('/aid'.length)), {
      headers: buildUserApiAuthHeaders(), credentials: 'same-origin', signal: controller.signal
    }).then(async response => {
      if (!response.ok || !response.headers.get('content-type')?.toLowerCase().startsWith('image/')) throw new Error('参考图暂不可用')
      const blob = await response.blob()
      if (controller.signal.aborted) return
      objectUrl = createTrackedObjectUrl(blob)
      setImage(objectUrl)
    }).catch(() => {
      if (!controller.signal.aborted) setFailed(true)
    }).finally(() => window.clearTimeout(timeout))
    return () => { window.clearTimeout(timeout); controller.abort(); if (objectUrl) revokeObjectUrl(objectUrl) }
  }, [src, legacy])
  const fallback = <span className="setting-reference-unavailable"><PictureOutlined aria-hidden /><span>参考图暂不可用</span></span>
  return failed ? fallback : image ? <ShimmerImage src={image} alt={label}
    wrapperClass="setting-select-option-thumb__shimmer" imgClass="option-thumb-img" objectFit="contain"
    revealDirection="fade" minShimmerMs={120} errorSlot={fallback} />
    : <span className="setting-reference-loading" role="status">加载参考图…</span>
}
