'use client'

import { useId } from 'react'
import { VideoCameraOutlined } from '@ant-design/icons'
import { Button } from 'antd'

export function DubbingVideoRequiredOverlay({
  variant,
  visible,
  onNavigate
}: {
  variant: 'card' | 'list'
  visible: boolean
  onNavigate: () => void
}) {
  const descriptionId = useId()

  return (
    <div
      className={`dubbing-body-mask dubbing-body-mask--${variant}${visible ? ' dubbing-body-mask--visible' : ''}`}
      onClick={(event) => {
        event.stopPropagation()
        onNavigate()
      }}
    >
      <div className="dubbing-mask-content">
        <Button
          type="primary"
          className={`dubbing-mask-btn${variant === 'list' ? ' dubbing-mask-btn--list' : ''}`}
          icon={variant === 'card' ? <VideoCameraOutlined /> : undefined}
          aria-describedby={descriptionId}
        >
          前往视频生成
        </Button>
        <p id={descriptionId} className="dubbing-mask-tip">
          添加分镜视频后即可进行音画同步
        </p>
      </div>
    </div>
  )
}
