'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { Modal, Button, InputNumber, Slider, message } from 'antd'
import { UploadOutlined, CloseOutlined, ReloadOutlined, CameraOutlined } from '@ant-design/icons'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import { ossRemoteUploadSingle } from '~/utils/businessApi'
import { ModelSelectDropdown, type ModelOption } from './ModelSelectDropdown'
import { MultiAngleCameraPreview, normalizeCameraYaw, type CameraPose } from './multi-angle-camera/MultiAngleCameraPreview'
import { buildMultiAnglePromptParts, type MultiAngleGeneratePayload } from '~/utils/multiAngleCameraPrompt'
import { DEFAULT_NINE_GRID_ANGLE_PROMPTS } from '~/utils/nineGridCameraAngles'
import { createTrackedObjectUrl, revokeObjectUrl } from '~/utils/objectUrl'
import './MultiAngleCameraModal.css'

interface Props {
  open: boolean
  imageUrl?: string
  /** 编辑分镜图：固定九宫格机位，禁用旋转与右侧调节 */
  fixedNineGrid?: boolean
  modelValue?: ModelOption
  modelOptions?: ModelOption[]
  modelExpanded?: boolean
  onOpenChange: (value: boolean) => void
  onModelExpandedChange?: (value: boolean) => void
  onSelectModel?: (model: ModelOption) => void
  onGenerate?: (payload: MultiAngleGeneratePayload) => void
  billingTarget?:
    | { kind: 'storyboard'; storyboardId: number; aspectRatio?: string }
    | { kind: 'form'; formId: number; aspectRatio?: string }
}

const DEFAULT_MODEL_VALUE: ModelOption = {id:'',name:'请选择模型',iconBg:'#60A5FA',prices:[]}

export function MultiAngleCameraModal(props: Props) {
  return <Modal open={props.open} width={1000} footer={null} title={null} closable={false}
    destroyOnHidden wrapClassName="create-flow-modal ma-camera-modal-wrap" className="ma-camera-modal"
    onCancel={() => props.onOpenChange(false)}>
    <MultiAngleCameraContent key={`${props.imageUrl || ''}:${Boolean(props.fixedNineGrid)}`} {...props} />
  </Modal>
}

function MultiAngleCameraContent({
  open, imageUrl = '', fixedNineGrid = false, modelValue = DEFAULT_MODEL_VALUE, modelOptions = [],
  modelExpanded = false, onOpenChange, onModelExpandedChange, onSelectModel, onGenerate, billingTarget
}: Props) {
  const [pose, setPose] = useState<CameraPose>({yaw: 0, pitch: 0})
  const [focalLength, setFocalLength] = useState(0)
  const [sourceUrl, setSourceUrl] = useState(imageUrl)
  const [previewUrl, setPreviewUrl] = useState('')
  const [failedUrl, setFailedUrl] = useState('')
  const [uploading, setUploading] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const uploadVersion = useRef(0)
  useEffect(() => {
    if (!open) uploadVersion.current += 1
    return () => { uploadVersion.current += 1 }
  }, [open])
  useEffect(() => () => { if (previewUrl) revokeObjectUrl(previewUrl) }, [previewUrl])
  const displayUrl = previewUrl || sourceUrl
  const imageFailed = Boolean(displayUrl && failedUrl === displayUrl)
  const promptParts = buildMultiAnglePromptParts(pose.yaw, pose.pitch, focalLength)
  const quoteRequest = useMemo(() => {
    if (!open || uploading || imageFailed || !billingTarget || !sourceUrl || !modelValue.id) return null
    const common = {imageUrl: sourceUrl, modelCode: modelValue.id, ...(billingTarget.aspectRatio ? {aspectRatio: billingTarget.aspectRatio} : {})}
    return billingTarget.kind === 'storyboard'
      ? {quoteType: fixedNineGrid ? 'STORYBOARD_MULTI_GRID_IMAGE' : 'STORYBOARD_MULTI_VIEW_IMAGE',
        payload: {...common, storyboardId: billingTarget.storyboardId, aspectRatio: billingTarget.aspectRatio || '1:1',
          angles: fixedNineGrid ? [...DEFAULT_NINE_GRID_ANGLE_PROMPTS] : [promptParts.concat]}}
      : {quoteType:'FORM_MULTI_VIEW_IMAGE', payload: {...common, formId: billingTarget.formId, anglePrompt: promptParts.concat}}
  }, [open, uploading, imageFailed, billingTarget, sourceUrl, modelValue.id, fixedNineGrid, promptParts.concat])
  const generationQuote = useBillingQuote(quoteRequest)

  async function upload(file: File) {
    if (!file.type.startsWith('image/')) { message.warning('请选择图片文件'); return }
    const version = ++uploadVersion.current
    setPreviewUrl(createTrackedObjectUrl(file))
    setUploading(true)
    try {
      const result = await ossRemoteUploadSingle(file, 'images')
      if (uploadVersion.current !== version) return
      if (!result.url) throw new Error('上传未返回图片地址')
      setSourceUrl(result.url)
    } catch (error) {
      if (uploadVersion.current !== version) return
      setPreviewUrl('')
      message.error(error instanceof Error ? error.message : '图片上传失败，请重试')
    } finally {
      if (uploadVersion.current === version) setUploading(false)
    }
  }

  function generate() {
    if (!sourceUrl || !modelValue.id || uploading || imageFailed) return
    const nineGridAngles = fixedNineGrid ? [...DEFAULT_NINE_GRID_ANGLE_PROMPTS] : undefined
    const parts = nineGridAngles ? {yawTags:'', pitchTags:'', focalTags:'', concat:nineGridAngles.join(' | ')} : promptParts
    onGenerate?.({
      mode: fixedNineGrid ? 'nineGridFixed' : 'single',
      horizontalRotation: pose.yaw, verticalAngle: pose.pitch, focalLength, imageUrl:sourceUrl,
      angles: nineGridAngles ? nineGridAngles.map((_, index) => ({angle:`格${index + 1}`,url:sourceUrl}))
        : [{angle:`水平 ${pose.yaw}°，垂直 ${pose.pitch}°`,url:sourceUrl}],
      multiAnglePromptConcat:parts.concat, multiAnglePromptParts:parts,
      ...(nineGridAngles ? {nineGridAngles} : {})
    })
    onOpenChange(false)
  }

  return <div className="ma-dialog">
    <header className="ma-header"><div><h2 className="ma-title">多机位</h2><p>围绕同一主体，探索不同拍摄角度</p></div>
      <button type="button" className="ma-close" aria-label="关闭多机位" onClick={() => onOpenChange(false)}><CloseOutlined /></button>
    </header>
    <div className="ma-layout">
      <section className="ma-viewport">
        <div className="ma-preview-heading"><span>{fixedNineGrid ? '九宫格机位' : '拍摄机位'}</span><small>{fixedNineGrid ? '固定 9 个角度' : '拖动相机或背景调整机位'}</small></div>
        <MultiAngleCameraPreview imageUrl={displayUrl} pose={pose} fixed={fixedNineGrid} onChange={setPose}
          onImageError={() => setFailedUrl(displayUrl)} />
        <p className={imageFailed ? 'ma-preview-note is-error' : 'ma-preview-note'} role={imageFailed ? 'alert' : undefined}>
          {uploading ? '正在上传参考图…' : imageFailed ? '参考图加载失败，请重新选择图片' : '机位示意用于选择角度，实际画面在生成后查看'}
        </p>
      </section>
      <aside className="ma-controls">
        <div className="ma-controls-heading"><h3>机位设置</h3><button type="button" disabled={fixedNineGrid} onClick={() => {setPose({yaw:0,pitch:0});setFocalLength(0)}}><ReloadOutlined /> 重置</button></div>
        <fieldset disabled={fixedNineGrid}>
          <legend className="sr-only">拍摄角度</legend>
          <div className="ma-angle-presets" aria-label="快捷机位">
            {[['正面',0],['右侧',90],['背面',180],['左侧',270]].map(([label,yaw]) => <button type="button" key={label}
              aria-pressed={pose.yaw === yaw} onClick={() => setPose({...pose,yaw:Number(yaw)})}>{label}</button>)}
          </div>
          <div className="ma-control"><label htmlFor="ma-yaw">水平角度</label><InputNumber id="ma-yaw" aria-label="水平角度" value={pose.yaw}
            min={0} max={359} controls={false} suffix="°" onChange={value => setPose({...pose,yaw:normalizeCameraYaw(Number(value ?? 0))})} />
            <Slider aria-label="水平角度" value={pose.yaw} min={0} max={359} disabled={fixedNineGrid} onChange={yaw => setPose({...pose,yaw})} />
          </div>
          <div className="ma-control"><label htmlFor="ma-pitch">垂直角度</label><InputNumber id="ma-pitch" aria-label="垂直角度" value={pose.pitch}
            min={-30} max={60} controls={false} suffix="°" onChange={value => setPose({...pose,pitch:Math.max(-30, Math.min(60, Number(value ?? 0)))})} />
            <Slider aria-label="垂直角度" value={pose.pitch} min={-30} max={60} disabled={fixedNineGrid} onChange={pitch => setPose({...pose,pitch})} />
          </div>
          <div className="ma-control"><span>拍摄景别</span><b>{focalLength < 3 ? '远景' : focalLength < 6 ? '中景' : focalLength < 9 ? '近景' : '特写'}</b>
            <Slider aria-label="拍摄景别" value={focalLength} min={0} max={10} disabled={fixedNineGrid} onChange={setFocalLength} />
            <div className="ma-focal-presets">{[['远景',0],['中景',5],['特写',10]].map(([label,value]) => <button type="button" key={label}
              aria-pressed={focalLength === value} onClick={() => setFocalLength(Number(value))}>{label}</button>)}</div>
          </div>
        </fieldset>
        <div className="ma-selection-summary"><CameraOutlined /><span>{fixedNineGrid ? '一次生成固定九宫格' : `水平 ${pose.yaw}° · 垂直 ${pose.pitch}°`}</span></div>
      </aside>
    </div>
    <footer className="ma-footer">
      <div className="ma-footer-source">
        {!fixedNineGrid ? <><input ref={input} type="file" hidden accept="image/*" onChange={event => { const file=event.target.files?.[0];event.target.value='';if(file)void upload(file) }} />
          <Button icon={<UploadOutlined />} loading={uploading} onClick={() => input.current?.click()}>更换参考图</Button></> : null}
        <div className="ma-model-select"><ModelSelectDropdown value={modelValue} options={modelOptions} expanded={modelExpanded}
          onToggle={() => onModelExpandedChange?.(!modelExpanded)} onClose={() => onModelExpandedChange?.(false)} onSelect={onSelectModel} /></div>
      </div>
      <div className="ma-footer-actions"><BillingQuoteHint quote={generationQuote.quote} loading={generationQuote.loading} error={generationQuote.error} active={Boolean(quoteRequest)} />
        <Button onClick={() => onOpenChange(false)}>取消</Button>
        <Button type="primary" disabled={!sourceUrl || !modelValue.id || uploading || imageFailed} onClick={generate}>开始生图</Button>
      </div>
    </footer>
  </div>
}

export default MultiAngleCameraModal
