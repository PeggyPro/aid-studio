'use client'

import { useRef, useState } from 'react'
import type { StudioCanvasNode } from '@/types/studio'
import type { UserModelListItem } from '@/types/business-api'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCanvasComposerModel } from '@/hooks/studio/useCanvasComposerModel'
import { useStudioCanvasModelsByFunc } from '@/hooks/studio/useStudioCatalog'
import { useCanvasQuote } from '@/hooks/studio/useCanvasQuote'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { canvasPrepareAudioAttachment, canvasQuoteAudio } from '@/utils/business/canvas'
import { audioFieldContract, audioInitialParameters, audioParameterErrors, audioParametersWithPrompt, audioParametersWithFiles, audioPersistParameters, audioSet } from '@/utils/audioModelContract'
import { StudioComposerFrame } from './StudioComposerFrame'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioModelPicker } from './StudioModelPicker'
import { StudioAudioParamsPopover } from './StudioAudioParamsPopover'
import { StudioAudioAttachmentButton } from './StudioAudioAttachmentButton'
import { studioAudioUploadTargets, validateStudioAudioAttachment } from '@/utils/studio/studioAudioInput'
import { CloseOutlined } from '@ant-design/icons'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'

export function StudioAudioComposer({ node: original }: { node: StudioCanvasNode }) {
  const runtime = useCanvasProjectRuntime()
  const workspaceId = String(runtime?.project?.workspace.workspaceId ?? '')
  const models = useStudioCanvasModelsByFunc('canvas.audio.generate', String(runtime?.project?.projectId ?? ''), !!runtime)
  const { node, commit } = useCanvasComposerModel(original, models.data, {
    enabled: !!runtime, loading: models.loading, preferredModelCode: models.defaultModelCode
  })
  const selected = models.data.find(model => model.modelCode === node.data.model)
  const options = node.data.generationOptions ?? {}
  const capabilities = selected?.availableCapabilities?.filter(cap => cap.audio) ?? []
  const selectedCode = options.audioCapability || selected?.capabilityCode || ''
  const capability = capabilities.find(cap => cap.code === selectedCode)
  const contract = capability?.audio
  const resourceAddress = (id: string) => runtime?.nodeResources(node.id).find(item => String(item.resourceId) === id)?.resourceUrl
  const parameters = audioParametersWithFiles(options.audioParameters ?? (contract ? audioInitialParameters(contract) : {}), options.audioFiles, resourceAddress)
  const [draft, setDraft] = useState({ nodeId: node.id, source: node.data.prompt, prompt: node.data.prompt })
  const uploadInFlight = useRef(false)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState('')
  const prompt = draft.nodeId === node.id && draft.source === node.data.prompt ? draft.prompt : node.data.prompt
  const values = contract ? audioParametersWithPrompt(contract, parameters, prompt) : parameters
  const errors = contract ? audioParameterErrors(contract, values) : {}
  for (const file of options.audioFiles ?? []) if (!file.referenceId && !resourceAddress(file.resourceId)) errors[file.field] = '素材已失效，请重新选择附件'
  const invalid = Object.keys(errors).length > 0
  const pricingModel: UserModelListItem | undefined = selected && capability ? {
    ...selected, capabilityCode: capability.code, audio: contract, billing: capability.billing
  } : selected
  const quote = useCanvasQuote('audio-node', workspaceId && contract && !invalid ? {
    workspaceId, resourceIds: options.audioResourceIds ?? [],
    request: { modelCode: selected!.modelCode, capabilityCode: capability!.code, parameters: values }
  } : null, canvasQuoteAudio)
  const disabledReason = !runtime ? '请进入已保存的画布项目'
    : uploading ? '附件上传并验证中'
    : !runtime.canEdit ? '当前账号只有查看权限'
      : !runtime.canUseFeature('canvas.generate') ? '当前账号没有画布生成权限'
        : runtime.isNodeBusy(node.id) ? '该节点正在生成中'
          : models.loading ? '正在读取音频模型'
            : !models.data.length ? '暂无已配置的可用音频模型'
              : !contract ? '请选择当前业务可用的音频能力'
                : invalid ? '请完善音频参数' : ''
  const save = (next: Record<string, unknown>) => {
    if (uploadInFlight.current || !runtime?.canMutate || runtime.isNodeBusy(node.id)) return
    const current = useStudioUiStore.getState().nodes.find(item => item.id === node.id)
    if (!current) return
    const persisted = contract ? audioPersistParameters(contract, next, current.data.generationOptions?.audioFiles ?? [], resourceAddress) : { parameters: next, files: [] }
    const nextIds = [...new Set(persisted.files.map(file => file.resourceId))]
    const priorIds = new Set(current.data.generationOptions?.audioResourceIds ?? [])
    commit({ canvasResourceIds: [...new Set([...(current.data.canvasResourceIds ?? []).filter(id => !priorIds.has(id)), ...nextIds])],
      generationOptions: { ...current.data.generationOptions, audioCapability: capability?.code,
      audioParameters: persisted.parameters, audioFiles: persisted.files, audioResourceIds: nextIds } })
  }
  const selectModel = (model: UserModelListItem, requestedCapability?: string) => {
    if (uploadInFlight.current || !runtime?.canMutate || runtime.isNodeBusy(node.id)) return
    const current = useStudioUiStore.getState().nodes.find(item => item.id === node.id)
    if (!current) return
    const currentOptions = current.data.generationOptions ?? {}
    const currentModel = models.data.find(item => item.modelCode === current.data.model)
    const currentCapability = currentOptions.audioCapability || currentModel?.capabilityCode || ''
    const currentCapabilityValid = model.availableCapabilities?.some(item => item.code === currentCapability && item.audio)
    // Reopening and choosing the current model must not reset authored parameters or attachments.
    if (current.data.model === model.modelCode && currentCapabilityValid && (requestedCapability === undefined || requestedCapability === currentCapability)) return
    const code = requestedCapability ?? model.capabilityCode ?? ''
    const cap = model.availableCapabilities?.find(item => item.code === code)
    commit({ model: model.modelCode, modelName: model.modelName, modelCostCredits: model.costCredits, modelIsFree: model.isFree,
      composerRefs: [], canvasResourceIds: current.data.canvasResourceIds?.filter(id => !(currentOptions.audioResourceIds ?? []).includes(id)), generationOptions: { ...currentOptions, audioCapability: code,
        audioParameters: cap?.audio ? audioInitialParameters(cap.audio) : {}, audioFiles: [], audioResourceIds: [] } })
  }
  const upload = async (path: string, file: File): Promise<void> => {
    if (uploadInFlight.current) throw new Error('请等待当前附件上传完成')
    if (!runtime || !contract || !selected || !capability) throw new Error('请先选择音频能力')
    const rule = audioFieldContract(contract, path)
    if (!rule) throw new Error('当前参数不支持附件')
    if (!runtime.canMutate || !runtime.canUseFeature('canvas.generate') || runtime.isNodeBusy(node.id)) throw new Error('当前音频节点不可编辑')
    if (rule.mediaType === 'AUDIO' || rule.mediaType === 'IMAGE' || rule.mediaType === 'VIDEO') validateStudioAudioAttachment(file, rule)
    uploadInFlight.current = true
    setUploading(true)
    try {
      const scope = useStudioUiStore.getState().scopeKey
      const resource = await runtime.uploadResource(file, rule.mediaType)
      const receipt = rule.prepareUpload ? await canvasPrepareAudioAttachment({
        workspaceId, modelCode: selected.modelCode, capabilityCode: capability.code,
        field: path, resourceId: String(resource.resourceId)
      }) : null
      const store = useStudioUiStore.getState()
      const latest = store.nodes.find(item => item.id === node.id)
      if (store.scopeKey !== scope || !latest || latest.data.model && latest.data.model !== selected.modelCode
        || latest.data.generationOptions?.audioCapability && latest.data.generationOptions.audioCapability !== capability.code) {
        throw new Error('模型或画布已切换，请重新选择附件')
      }
      const files = [...(latest.data.generationOptions?.audioFiles ?? []).filter(item => item.field !== path),
        { field: path, resourceId: String(resource.resourceId), ...(receipt?.referenceId ? { referenceId: receipt.referenceId } : {}) }]
      // 合并上传期间的其他编辑，不能用上传前的整个参数快照覆盖当前表单。
      store.updateNodeData(node.id, { model: selected.modelCode, modelName: selected.modelName,
        canvasResourceIds: [...new Set([...(latest.data.canvasResourceIds ?? []).filter(id => !(latest.data.generationOptions?.audioResourceIds ?? []).includes(id)), ...files.map(item => item.resourceId)])],
        generationOptions: { ...latest.data.generationOptions, audioCapability: capability.code, audioFiles: files,
          audioResourceIds: [...new Set(files.map(item => item.resourceId))] } })
    } finally {
      uploadInFlight.current = false
      setUploading(false)
    }
  }
  const controlsReason = !runtime?.canMutate ? '当前音频节点不可编辑' : runtime.isNodeBusy(node.id) ? '该节点正在生成中' : ''
  const targets = contract ? studioAudioUploadTargets(contract, parameters) : []
  return <StudioComposerFrame node={node} pricingModel={pricingModel} pricingLoading={models.loading}
    headerTools={<StudioAudioAttachmentButton key={`${node.id}:${node.data.model}:${selectedCode}`} contract={contract} value={parameters} uploading={uploading}
      disabledReason={controlsReason} onUpload={upload} onError={setUploadError} />}
    showCredits attachmentControl={null} allowEmptySubmit={!!contract && !contract.promptPath}
    placeholder={contract?.promptPath.includes('text') ? '输入要合成的配音文本' : contract?.promptPath ? '描述当前能力要生成的内容' : '在音频设置中填写歌词或编辑内容'}
    promptTrailing={<>
      {!!options.audioFiles?.length && <div className="studio-audio-attachments" aria-label="已上传的附件">{options.audioFiles.map(file => {
        const label = targets.find(target => target.path === file.field)?.label || (contract && audioFieldContract(contract, file.field)?.mediaType === 'IMAGE' ? '参考图片' : '音频附件')
        return <span key={file.field}>
        <span>{label} · 已上传</span>
        <StudioCanvasTooltip title={`移除${label}`}><button type="button" aria-label={`移除${label}`} disabled={uploading || !!controlsReason}
          onClick={() => save(audioSet(parameters, file.field, undefined))}><CloseOutlined /></button></StudioCanvasTooltip>
      </span>})}</div>}
      {uploadError ? <p role="alert" className="studio-audio-capability__error">{uploadError}</p> : null}
      {contract?.promptPath && errors[contract.promptPath] ? <p role="alert" className="studio-audio-capability__error">{errors[contract.promptPath]}</p> : null}
    </>}
    quoteOverride={quote} onPromptDraftChange={prompt => setDraft({ nodeId: node.id, source: node.data.prompt, prompt })}
    submitDisabledReason={disabledReason}
    onSubmit={async submitted => {
      if (!runtime || !contract || uploadInFlight.current) return false
      save(audioParametersWithPrompt(contract, parameters, submitted))
      return runtime.generateNode('audio', node.id, submitted)
    }}
    bottomLeft={<fieldset disabled={uploading || !!controlsReason} style={{ display: 'contents', border: 0, padding: 0, margin: 0 }}>
      <StudioModelPicker value={node.data.model} mediaKind="audio" options={models.data} optionsLoading={models.loading}
        optionsError={models.error} syncDefaults={false} preserveInvalidSelection
        selectedModelMeta={{ modelName: node.data.modelName, isFree: node.data.modelIsFree, costCredits: node.data.modelCostCredits }}
        onChange={(_, item) => selectModel(item)} />
      {selected && capabilities.length > 0 && <StudioCanvasDropdownPopover nodePlacement ariaLabel="音频生成能力" activeKey={selectedCode}
        items={capabilities.map(cap => ({ key: cap.code, label: cap.label || cap.code }))}
        onSelect={code => selectModel(selected, code)} trigger={<StudioComposerChip label={capability?.label || '选择能力'} title="音频生成能力" />} />}
      {contract && <StudioAudioParamsPopover contract={contract} value={parameters} errors={errors} onChange={save}
        onUpload={upload} disabled={uploading || !!controlsReason} />}
    </fieldset>} />
}
