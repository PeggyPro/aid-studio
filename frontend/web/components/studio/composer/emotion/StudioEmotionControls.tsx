'use client'
import { useState } from 'react'
import { StudioEmotionGrid } from './StudioEmotionGrid'
import { StudioEmotionHeadScene } from './StudioEmotionHeadScene'
export function StudioEmotionControls({
  affinity,
  arousal,
  onChange
}: {
  affinity: number
  arousal: number
  onChange: (affinity: number, arousal: number) => void
}) {
  const [preview, setPreview] = useState<{ affinity: number; arousal: number } | null>(null)
  return (
    <div className="studio-emotion-panel__body">
      <StudioEmotionHeadScene
        affinity={preview?.affinity ?? affinity}
        arousal={preview?.arousal ?? arousal}
      />
      <StudioEmotionGrid affinity={affinity} arousal={arousal} onChange={onChange} onPreview={setPreview} />
    </div>
  )
}
