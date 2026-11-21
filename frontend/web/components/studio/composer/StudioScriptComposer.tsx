'use client'

import { StudioCanvasPopover } from '@/components/studio/canvas/StudioCanvasPopover'
import { RobotOutlined } from '@ant-design/icons'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'
import { useStudioAgents, useStudioModels } from '@/hooks/studio/useStudioCatalog'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioCanvasNode } from '@/types/studio'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioComposerFrame } from './StudioComposerFrame'
import { StudioModelPicker } from './StudioModelPicker'
import { StudioStoryboardScriptEntrances } from './StudioStoryboardScriptEntrances'
import { resolveStudioComposerGenSceneCode, resolveStudioComposerModelFromNode } from '@/utils/studio/studioComposerDefaultModel'
import { getStudioComposerPopupContainer } from '@/utils/studio/studioComposerPopupContainer'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'

export function StudioScriptComposer({ node }: { node: StudioCanvasNode }) {
  const canvasRuntime = useCanvasProjectRuntime()
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const { data: agents, loading: agentsLoading, error: agentsError } = useStudioAgents()
  const textModels = useStudioModels('text', {
    enabled: Boolean(canvasRuntime),
    canvas: Boolean(canvasRuntime)
  })
  const activeAgent = agents.find((agent) => agent.agentCode === node.data.composerAgentCode)
  return (
    <StudioComposerFrame
      node={node}
      pricingModel={canvasRuntime ? resolveStudioComposerModelFromNode(textModels.data, node) : undefined}
      topLeft={<StudioStoryboardScriptEntrances node={node} />}
      bottomLeft={
        <>
          <StudioModelPicker
            value={node.data.model}
            mediaKind="text"
            sceneCode={resolveStudioComposerGenSceneCode(node.data)}
            options={canvasRuntime ? textModels.data : undefined}
            onChange={(model, item) => updateNodeData(node.id, { model, modelName: item.modelName, modelCostCredits: item.costCredits, modelIsFree: item.isFree })}
          />
          <StudioCanvasPopover
            trigger="click"
            nodePlacement
            arrow={false}
            getPopupContainer={getStudioComposerPopupContainer}
            classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas' }}
            content={(
              <div className="studio-composer-menu">
                {agentsLoading ? <span>正在读取可用智能体…</span> : null}
                {!agentsLoading && agentsError ? <span>{agentsError}</span> : null}
                {!agentsLoading && !agentsError && !agents.length ? <span>暂无可用智能体</span> : null}
                {agents.map((agent) => (
                  <button
                    key={agent.agentCode}
                    type="button"
                    onClick={() => updateNodeData(node.id, {
                      composerAgentCode: agent.agentCode,
                      composerAgentName: agent.name
                    })}
                  >
                    {agent.name}
                  </button>
                ))}
              </div>
            )}
          >
            <StudioComposerChip icon={<StudioChromeIcon name="agentChat" fallback={<RobotOutlined />} />} label={activeAgent?.name ?? node.data.composerAgentName ?? '智能体'} />
          </StudioCanvasPopover>
        </>
      }
    />
  )
}
