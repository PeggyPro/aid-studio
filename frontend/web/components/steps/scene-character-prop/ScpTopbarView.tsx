'use client'

import { Button, Dropdown, Tooltip } from 'antd'
import { HomeSegmented } from '~/components/home/theme/HomeSegmented'
import { FlowActionIcon } from '~/components/common/FlowActionIcon'
import {
  DeleteOutlined,
  DownOutlined,
  IdcardOutlined,
  LoadingOutlined,
  PictureOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import { assetUrl } from '~/utils/assetUrl'
import { useOnboardingPrepareFlags } from '~/utils/onboardingPrepareAnchor'
import iconAddSceneRaw from '~/assets/img/icon/extract.svg'
import iconAddCharacterRaw from '~/assets/img/icon/character.svg'
import iconAddPropRaw from '~/assets/img/icon/prop.svg'
import { tabs } from './useScpDerived'
import type { ScpCtx } from './types'

const iconAddScene = assetUrl(iconAddSceneRaw)
const iconAddCharacter = assetUrl(iconAddCharacterRaw)
const iconAddProp = assetUrl(iconAddPropRaw)

/** 顶部切换：场景 / 角色 / 道具 + 顶栏「添加」与「批量操作」 */
export function ScpTopbarView({ ctx }: { ctx: ScpCtx }) {
  const scpOnboardingShowBatchOps = useOnboardingPrepareFlags((s) => s.scpOnboardingShowBatchOps)
  const isExtracting = ctx.isExtracting()
  const localValue = ctx.localValue.value
  const activeTab = ctx.activeTab.value

  /** 顶栏「添加场景/角色/道具」：默认图标 */
  const topbarAddIconNor =
    activeTab === 'scene' ? iconAddScene : activeTab === 'character' ? iconAddCharacter : iconAddProp

  return (
    <div className="scp-topbar">
      <div data-onboarding="scp-tabs">
        <HomeSegmented value={activeTab} className="flow-segmented" ariaLabel="素材准备切换" disabled={isExtracting}
          options={tabs.map((tab) => ({value:tab.key,label:tab.label,busy:ctx.isTabLoading(tab.key),adornment:ctx.isTabLoading(tab.key) ? <LoadingOutlined className="scp-tab-loading flow-generation-spinner" spin /> : undefined}))}
          onChange={(value) => { if (!isExtracting) ctx.activeTab.set(value) }} />
      </div>
      {!isExtracting &&
      ((activeTab === 'scene' && localValue.scenes.length > 0) ||
        (activeTab === 'character' && localValue.characters.length > 0) ||
        (activeTab === 'prop' && localValue.props.length > 0)) ? (
        <p className="scp-topbar-hint">
          {activeTab === 'scene' ? (
            <>(已识别 {localValue.scenes.length} 个场景，可选择生成新场景图或导入已有图片)</>
          ) : activeTab === 'character' ? (
            <>(已识别 {localValue.characters.length} 个角色，可选择生成新角色图或导入已有图片)</>
          ) : (
            <>(已识别 {localValue.props.length} 个道具，可选择生成新道具图或导入已有图片)</>
          )}
        </p>
      ) : null}
      <div className="scp-topbar__right">
        <Button
          className="scp-topbar-add-btn"
          disabled={ctx.topbarAddDisabled()}
          loading={ctx.manualAssetAdding.value}
          onClick={ctx.handleEmptyAssetAddClick}
          icon={
            <span className="scp-topbar-add-btn__ico-wrap">
              <FlowActionIcon
                src={topbarAddIconNor}
                className="scp-topbar-add-btn__ico"
              />
            </span>
          }
        >
          {ctx.emptyAssetAddLabel()}
        </Button>
        {!isExtracting && (ctx.showBatchGenerateTopbarBtn() || scpOnboardingShowBatchOps) ? (
          <Dropdown
            open={ctx.batchOpsDropdownOpen.value}
            onOpenChange={(v) => ctx.batchOpsDropdownOpen.set(v)}
            trigger={['click']}
            placement="bottomRight"
            classNames={{ root: 'scp-topbar-ops-dropdown-overlay' }}
            popupRender={() => (
              <div className="scp-topbar-ops-panel" role="menu" aria-label="批量操作">
                <div className="scp-topbar-ops-panel__list">
                  {ctx.batchFormGenerateMenuDisabled() && ctx.batchFormGenerateDisabledTooltip() ? (
                    <Tooltip
                      title={ctx.batchFormGenerateDisabledTooltip()}
                      placement="left"
                      mouseEnterDelay={0.2}
                    >
                      <span className="scp-topbar-ops-panel__item-wrap">
                        <button
                          type="button"
                          className="scp-topbar-ops-panel__item scp-topbar-ops-panel__item--disabled"
                          data-onboarding="scp-batch-form-generate"
                          disabled
                          role="menuitem"
                        >
                          <ThunderboltOutlined />
                          <span>{ctx.batchFormGenerateMenuLabel()}</span>
                        </button>
                      </span>
                    </Tooltip>
                  ) : (
                    <button
                      type="button"
                      className="scp-topbar-ops-panel__item"
                      data-onboarding="scp-batch-form-generate"
                      role="menuitem"
                      onClick={ctx.handleBatchFormGenerateClick}
                    >
                      <ThunderboltOutlined />
                      <span>{ctx.batchFormGenerateMenuLabel()}</span>
                    </button>
                  )}
                  <button
                    type="button"
                    className="scp-topbar-ops-panel__item"
                    data-onboarding="scp-batch-image-generate"
                    role="menuitem"
                    disabled={ctx.batchImageGenerateMenuDisabled()}
                    onClick={ctx.handleBatchImageGenerateClick}
                  >
                    <PictureOutlined />
                    <span>{ctx.batchImageGenerateMenuLabel()}</span>
                  </button>
                  {activeTab === 'character' ? (
                    <button
                      type="button"
                      className="scp-topbar-ops-panel__item"
                      role="menuitem"
                      disabled={ctx.batchCardGenerateMenuDisabled()}
                      onClick={ctx.handleBatchCardGenerateClick}
                    >
                      <IdcardOutlined />
                      <span>批量生成设定卡</span>
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className="scp-topbar-ops-panel__item scp-topbar-ops-panel__item--danger"
                    role="menuitem"
                    disabled={ctx.batchDeleteMenuDisabled() || ctx.batchDeleteSubmitting.value}
                    onClick={ctx.handleBatchDeleteClick}
                  >
                    <DeleteOutlined />
                    <span>批量删除</span>
                  </button>
                </div>
              </div>
            )}
          >
            <Button
              type="default"
              className="scp-topbar-batch-btn scp-topbar-ops-btn"
              data-onboarding="scp-batch-ops"
              loading={
                ctx.batchGenerateTopbarLoading() ||
                ctx.batchDeleteSubmitting.value ||
                ctx.batchCardGenerateSubmitting.value
              }
            >
              批量操作
              <DownOutlined
                className={`scp-topbar-ops-btn__arrow${
                  ctx.batchOpsDropdownOpen.value ? ' scp-topbar-ops-btn__arrow--open' : ''
                }`}
              />
            </Button>
          </Dropdown>
        ) : null}
      </div>
    </div>
  )
}
