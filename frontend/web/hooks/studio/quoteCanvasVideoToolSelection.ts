import { Modal } from 'antd'
import type { BillingQuoteVO } from '@/types/business-api'
import type { CanvasProjectWorkspaceRuntime } from './useCanvasProjectWorkspace'
import type { RunStudioVideoOperationInput } from './useStudioVideoOperation'
import { canvasUserModelListByFuncCodes } from '@/utils/business/model'
import { modelSupportsCapability } from '@/utils/modelCapability'
import { studioNodeToComposerRef } from '@/utils/studio/studioReferencePicker'
import { canvasQuoteVideoTool } from '@/utils/business/canvas'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'
import { canvasVideoToolRequest, canvasVideoToolWithModelSettings, canvasVideoToolRoute } from './runCanvasVideoTool'

/** The same business-model selection and authoritative quote used by canvas video submission. */
export async function quoteCanvasVideoToolSelection(
  input: RunStudioVideoOperationInput,
  runtime: CanvasProjectWorkspaceRuntime
): Promise<{ modelCode: string; modelName: string; quote: BillingQuoteVO }> {
  const route = canvasVideoToolRoute(input.operation)
  const projectId = runtime.project?.projectId
  const workspaceId = runtime.project?.workspace?.workspaceId
  if (!route || !projectId || !workspaceId) throw new Error('画布视频工具或项目暂不可用')
  const groups = await canvasUserModelListByFuncCodes([route.funcCode], String(projectId))
  const group = groups.find((item) => item.funcCode === route.funcCode)
  const models = (group?.models ?? []).filter((item) => modelSupportsCapability(item, route.capabilityCode))
  const requested = String(input.modelCode || '').trim()
  const selected = requested
    ? models.find((item) => item.modelCode === requested)
    : models.find((item) => item.modelCode === group?.defaultModelCode) ?? models[0]
  if (!selected) throw new Error(requested ? '所选模型未绑定此视频工具，请重新选择' : '此视频工具暂无可用模型')
  const source = await runtime.resolveResourceReference(studioNodeToComposerRef(input.source))
  if (source.mediaType !== 'VIDEO') throw new Error('源视频资源类型错误')
  const replacementIds = input.operation === 'SUBJECT_REPLACE'
    ? (input.subjectEdits ?? []).map((mark) => mark.replacementResourceId) : []
  if (replacementIds.some((id) => !id)) throw new Error('请重新登记主体替换参考图')
  const imageIds = [...new Set(replacementIds.filter((id): id is string => Boolean(id)))]
  // Resource registration may save the source node and advance its version.
  const sourceExpectedVersion = runtime.getNodeVersion(input.source.id)
  if (!sourceExpectedVersion) throw new Error('源视频节点尚未保存')
  const quote = await canvasQuoteVideoTool({
    workspaceId,
    resourceIds: [source.resourceId, ...imageIds],
    videoTool: {
      operation: route.operation,
      sourceObjectId: input.source.id,
      sourceExpectedVersion,
      sourceResourceId: source.resourceId
    },
    request: await canvasVideoToolRequest(canvasVideoToolWithModelSettings(input, selected), selected.modelCode, route.capabilityCode, imageIds)
  })
  if (studioCanvasQuoteCredits(quote) == null) throw new Error('当前模型和参数没有可用计费规则')
  return { modelCode: selected.modelCode, modelName: selected.modelName || selected.modelCode, quote }
}

/** Quoting does not create a node, task or charge. The caller submits only after confirmation. */
export async function confirmCanvasVideoToolQuote(
  input: RunStudioVideoOperationInput,
  runtime: CanvasProjectWorkspaceRuntime
): Promise<string | null> {
  const priced = await quoteCanvasVideoToolSelection(input, runtime)
  const credits = studioCanvasQuoteCredits(priced.quote)
  if (credits == null) throw new Error('当前模型和参数没有可用计费规则')
  return new Promise((resolve) => {
    Modal.confirm({
      title: `确认${input.label}`,
      content: `${priced.modelName} · 本次预计${credits === 0 ? '免费' : `消耗 ${credits} 积分`}。实际费用以任务结算为准。`,
      okText: '确认处理', cancelText: '返回',
      onOk: () => resolve(priced.modelCode),
      onCancel: () => resolve(null)
    })
  })
}
