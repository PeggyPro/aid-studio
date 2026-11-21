'use client'

import { QuestionCircleOutlined } from '@ant-design/icons'
import { Tooltip } from 'antd'

export function StudioComposerAdvancedSettings({
  autoLinkEnabled,
  onAutoLinkChange
}: {
  autoLinkEnabled: boolean
  onAutoLinkChange: (next: boolean) => void
}) {
  return (
    <div className="studio-composer-advanced">
      <div className="studio-composer-advanced__title">高级设置</div>
      <div className="studio-composer-advanced__row">
        <div className="studio-composer-advanced__label">
          <span>智能引用 AutoLink</span>
          <Tooltip title="自动匹配画布图片/视频节点名称，一键引用，省去手动 @ 的麻烦">
            <button type="button" className="studio-composer-advanced__help" aria-label="智能引用说明">
              <QuestionCircleOutlined />
            </button>
          </Tooltip>
        </div>
        <button
          type="button"
          role="switch"
          aria-label="智能引用 AutoLink"
          aria-checked={autoLinkEnabled}
          className={`studio-composer-advanced__switch${autoLinkEnabled ? ' is-on' : ''}`}
          onClick={() => onAutoLinkChange(!autoLinkEnabled)}
        >
          <i />
        </button>
      </div>
    </div>
  )
}
