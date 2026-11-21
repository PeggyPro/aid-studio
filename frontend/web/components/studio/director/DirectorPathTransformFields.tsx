'use client'

import { useEffect, useState } from 'react'
import { Button, InputNumber, Space } from 'antd'
import type { DirectorPathClip, DirectorTransformTuple as Vec } from '@/types/studio'
import { retimeSelectedClip, transformDirectorPathClip } from '@/utils/studio/directorPathClipOps'

const ZERO: Vec = [0, 0, 0]
const ONE: Vec = [1, 1, 1]

export function DirectorPathTransformFields({
  clip,
  disabled,
  onChange,
}: {
  clip?: DirectorPathClip
  disabled?: boolean
  onChange: (clip: DirectorPathClip) => void
}) {
  const [position, setPosition] = useState<Vec>(ZERO)
  const [rotationDeg, setRotationDeg] = useState<Vec>(ZERO)
  const [scale, setScale] = useState<Vec>(ONE)

  useEffect(() => {
    setPosition([0, 0, 0])
    setRotationDeg([0, 0, 0])
    setScale([1, 1, 1])
  }, [clip?.id])

  const vectorFields = (label: string, value: Vec, setValue: (next: Vec) => void, step: number) => (
    <div className="director-motion-vector" aria-label={label}>
      <span>{label}</span>
      <div>
        {(['X', 'Y', 'Z'] as const).map((axis, index) => (
          <label key={axis}>
            <span>{axis}</span>
            <InputNumber
              size="small"
              value={value[index]}
              step={step}
              disabled={disabled || !clip}
              aria-label={`${label}${axis}`}
              onChange={(next) => {
                if (next === null) return
                const copy = [...value] as Vec
                copy[index] = next
                setValue(copy)
              }}
            />
          </label>
        ))}
      </div>
    </div>
  )

  return (
    <section className="director-control-section director-control-section--flat director-motion-transform" aria-label="轨迹片段变换">
      <div className="director-section-title"><strong>片段变换</strong></div>
      <label className="director-field">
        <span>时长</span>
        <Space.Compact><InputNumber
          size="small"
          min={0.2}
          step={0.1}
          value={clip ? Number((clip.end - clip.start).toFixed(3)) : undefined}
          disabled={disabled || !clip}
          onChange={(value) => value !== null && clip && onChange(retimeSelectedClip(clip, value))}
        /><span className="director-field-unit">秒</span></Space.Compact>
      </label>
      {vectorFields('位置', position, setPosition, 0.1)}
      {vectorFields('旋转', rotationDeg, setRotationDeg, 1)}
      {vectorFields('缩放', scale, setScale, 0.1)}
      <Button
        block
        disabled={disabled || !clip}
        onClick={() => {
          if (!clip) return
          onChange(transformDirectorPathClip(clip, { position, rotationDeg, scale }))
          setPosition([0, 0, 0])
          setRotationDeg([0, 0, 0])
          setScale([1, 1, 1])
        }}
      >
        应用变换
      </Button>
      {!clip ? <p className="director-field__hint">请先在本机位轨道中选择片段</p> : null}
    </section>
  )
}
