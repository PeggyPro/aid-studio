'use client'

import { Button, Dropdown, Tooltip } from 'antd'
import { ExportOutlined } from '@ant-design/icons'
import { useStudioFlowPublishExportContext } from './StudioFlowPublishExportProvider'

export function StudioFlowDeliveryMenu({
  compact = false,
  disabled = false
}: {
  compact?: boolean
  disabled?: boolean
}) {
  const publishExport = useStudioFlowPublishExportContext()
  if (!publishExport) return null

  const {
    exportMenuOpen,
    onExportMenuOpenChange,
    previewExportBusy,
    onExportFullVideo,
    onExportSegments,
    publishToCasePlazaDisabled,
    publishToCasePlazaTooltip,
    getPublishTooltipPopupContainer,
    onPublishToCasePlaza
  } = publishExport

  const menu = (
    <div className="studio-flow-delivery-menu" role="menu" aria-label="导出与发布">
      <div className="studio-flow-delivery-menu__title">导出与发布</div>
      <button
        type="button"
        className="studio-flow-delivery-menu__btn"
        role="menuitem"
        disabled={previewExportBusy}
        onClick={() => void onExportFullVideo()}
      >
        导出完整视频
      </button>
      <button
        type="button"
        className="studio-flow-delivery-menu__btn studio-flow-delivery-menu__btn--secondary"
        role="menuitem"
        disabled={previewExportBusy}
        onClick={() => void onExportSegments()}
      >
        导出分段素材
      </button>
      {publishToCasePlazaDisabled ? (
        <Tooltip
          title={publishToCasePlazaTooltip}
          placement="left"
          styles={{ root: { zIndex: 11000, maxWidth: '280px' } }}
          getPopupContainer={getPublishTooltipPopupContainer}
        >
          <span className="studio-flow-delivery-menu__btn-wrap">
            <button type="button" className="studio-flow-delivery-menu__btn studio-flow-delivery-menu__btn--secondary" role="menuitem" disabled>
              发布至案例广场
            </button>
          </span>
        </Tooltip>
      ) : (
        <button
          type="button"
          className="studio-flow-delivery-menu__btn studio-flow-delivery-menu__btn--secondary"
          role="menuitem"
          disabled={previewExportBusy}
          onClick={onPublishToCasePlaza}
        >
          发布至案例广场
        </button>
      )}
    </div>
  )

  return (
    <Dropdown
      open={exportMenuOpen}
      trigger={['click']}
      placement="bottomRight"
      disabled={disabled}
      classNames={{ root: 'studio-flow-delivery-dropdown' }}
      onOpenChange={(open) => void onExportMenuOpenChange(open)}
      popupRender={() => menu}
    >
      <Button
        type={compact ? 'default' : 'primary'}
        size={compact ? 'middle' : 'small'}
        className={compact ? 'studio-flow-delivery-trigger--compact' : 'studio-flow-delivery-trigger'}
        icon={<ExportOutlined />}
        disabled={disabled || previewExportBusy}
        loading={previewExportBusy}
      >
        {compact ? '交付' : '导出/发布'}
      </Button>
    </Dropdown>
  )
}
