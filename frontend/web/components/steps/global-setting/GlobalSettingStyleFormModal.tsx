'use client'

import { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { Button, Form, Input, Modal, Select, message } from 'antd'
import { CheckCircleOutlined, PictureOutlined, ScanOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { userAssetCustomCreate } from '~/utils/businessApi'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import {
  getActiveStyleAnalysis, getStyleAnalysisStatus, submitStyleAnalysis,
  type ActiveStyleAnalysis, type StyleAnalysisResult
} from '~/utils/business/styleAnalysis'
import './style-analysis-wait.css'

const ANALYSIS_FIELDS = ['assetName', 'personalityDesc', 'promptText', 'remark'] as const
type ActiveLookup = 'idle' | 'checking' | 'error' | ActiveStyleAnalysis
type LocalCoverPreview = { src: string; uploadedUrl?: string }

export interface GlobalSettingStyleFormModalHandle {
  /** 原 openCreateStyleModal：重置表单并打开弹窗（锁定/提交中判断由调用方完成） */
  openCreateStyleModal: () => void
  /** 提交中禁止重复打开（原 creatingStyle 闸门） */
  isCreatingStyle: () => boolean
}

export interface GlobalSettingStyleFormModalProps {
  /** 提交时二次校验风格锁（弹窗打开期间锁可能生效） */
  styleLocked: boolean
  styleLockActionMessage: string
  /** 创建成功（弹窗已关、toast 已出）后由父级刷新列表并选中新风格 */
  onCreated: (createdId: number) => Promise<void>
}

/** GlobalSetting「新增风格」弹窗（自 GlobalSetting.vue 原样拆出：表单、封面上传、提交） */
export const GlobalSettingStyleFormModal = forwardRef<
  GlobalSettingStyleFormModalHandle,
  GlobalSettingStyleFormModalProps
>(function GlobalSettingStyleFormModal({ styleLocked, styleLockActionMessage, onCreated }, ref) {
  const styleLockedRef = useRef(styleLocked)
  styleLockedRef.current = styleLocked
  const styleLockActionMessageRef = useRef(styleLockActionMessage)
  styleLockActionMessageRef.current = styleLockActionMessage

  const [styleFormOpen, setStyleFormOpen] = useState(false)
  const [creatingStyle, setCreatingStyle] = useState(false)
  const creatingStyleRef = useRef(false)
  const [styleCoverUploading, setStyleCoverUploading] = useState(false)
  const styleCoverUploadingRef = useRef(false)
  const session = useRef(0)
  const activeAnalysis = useRef(false)
  const analysisRequestId = useRef('')
  const [analyzing, setAnalyzing] = useState(false)
  const [analysisError, setAnalysisError] = useState('')
  const [analysisComplete, setAnalysisComplete] = useState(false)
  const [manualMode, setManualMode] = useState(false)
  const [activeLookup, setActiveLookup] = useState<ActiveLookup>('idle')
  const activeLookupRef = useRef<ActiveLookup>('idle')
  const editedFields = useRef(new Set<keyof StyleAnalysisResult>())
  const waitingForAnalysis = typeof activeLookup === 'object'
  const coverDisabled = styleCoverUploading || analyzing || activeLookup === 'checking' || creatingStyle
  const [localCoverPreview, setLocalCoverPreview] = useState<LocalCoverPreview | null>(null)
  const localCoverPreviewRef = useRef<LocalCoverPreview | null>(null)
  useEffect(() => () => {
    session.current++
    const preview = localCoverPreviewRef.current
    if (preview) URL.revokeObjectURL(preview.src)
  }, [])

  const styleCoverInputRef = useRef<HTMLInputElement | null>(null)
  const [styleForm, setStyleForm] = useState({
    assetType: 'style',
    assetName: '',
    personalityDesc: '',
    promptText: '',
    imageUrl: '',
    remark: ''
  })
  const styleFormRef = useRef(styleForm)
  styleFormRef.current = styleForm

  const styleTypeSelectOptions = useMemo(() => [{ value: 'style', label: '风格' }], [])

  function patchStyleForm(patch: Partial<typeof styleForm>) {
    const next = { ...styleFormRef.current, ...patch }
    styleFormRef.current = next
    setStyleForm(next)
  }

  function replaceLocalCoverPreview(preview: LocalCoverPreview | null) {
    const previous = localCoverPreviewRef.current
    if (previous && previous.src !== preview?.src) URL.revokeObjectURL(previous.src)
    localCoverPreviewRef.current = preview
    setLocalCoverPreview(preview)
  }

  function resetAnalysis() {
    session.current++
    activeAnalysis.current = false
    analysisRequestId.current = ''
    setAnalyzing(false)
    setAnalysisError('')
    setAnalysisComplete(false)
    updateActiveLookup('idle')
  }

  function isCoverBusy() {
    return styleCoverUploadingRef.current || activeAnalysis.current || activeLookupRef.current === 'checking' || creatingStyleRef.current
  }

  function updateActiveLookup(lookup: ActiveLookup) {
    activeLookupRef.current = lookup
    setActiveLookup(lookup)
  }

  async function checkActiveStyleAnalysis(conflictMessage?: string) {
    if (isCoverBusy()) return
    const generation = session.current
    updateActiveLookup('checking')
    try {
      const task = await getActiveStyleAnalysis()
      if (generation !== session.current) return
      updateActiveLookup('idle')
      if (task?.taskId) {
        const imageUrl = task.imageUrl ?? ''
        patchStyleForm({ imageUrl })
        void analyzeStyle(imageUrl, task.taskId)
      } else if (conflictMessage) setAnalysisError(conflictMessage)
    } catch {
      if (generation === session.current) updateActiveLookup('error')
    }
  }

  function resumeStyleAnalysis() {
    const task = activeLookupRef.current
    if (typeof task !== 'object' || activeAnalysis.current || creatingStyleRef.current) return
    const imageUrl = task.imageUrl ?? ''
    patchStyleForm({ imageUrl })
    updateActiveLookup('idle')
    void analyzeStyle(imageUrl, task.taskId)
  }

  function editStyleField(key: keyof StyleAnalysisResult, value: string) {
    if (creatingStyleRef.current) return
    editedFields.current.add(key)
    patchStyleForm({ [key]: value })
  }

  async function analyzeStyle(imageUrl: string, existingTaskId?: string) {
    if (activeAnalysis.current || creatingStyleRef.current || (!imageUrl && !existingTaskId)) return
    const generation = session.current
    const current = () => generation === session.current
    activeAnalysis.current = true
    setAnalyzing(true)
    setAnalysisError('')
    setAnalysisComplete(false)
    let taskId = existingTaskId
    let conflict = false
    try {
      if (!taskId) {
        analysisRequestId.current ||= globalThis.crypto?.randomUUID?.() ?? (Date.now() + '-' + Math.random().toString(36).slice(2))
      }
      let analysis = taskId
        ? await getStyleAnalysisStatus(taskId)
        : await submitStyleAnalysis(imageUrl, analysisRequestId.current)
      if (!current()) return
      taskId = analysis.taskId || taskId
      const deadline = Date.now() + 90_000
      while (analysis.status === 'PENDING' && taskId && current() && Date.now() < deadline) {
        await new Promise((resolve) => window.setTimeout(resolve, 1800))
        if (!current()) return
        analysis = await getStyleAnalysisStatus(taskId)
      }
      if (!current()) return
      if (analysis.status === 'PENDING' && taskId) {
        updateActiveLookup({ taskId, imageUrl })
        return
      }
      analysisRequestId.current = ''
      if (analysis.status !== 'SUCCEEDED' || !analysis.result) {
        setAnalysisError(analysis.message || '风格解析失败，请重试或手动填写。')
        return
      }
      const patch: Partial<StyleAnalysisResult> = {}
      for (const key of ANALYSIS_FIELDS) {
        if (!editedFields.current.has(key)) patch[key] = analysis.result[key]
      }
      patchStyleForm(patch)
      setAnalysisComplete(true)
    } catch (error) {
      if (current()) {
        const detail = error as { code?: number; msg?: string; message?: string; response?: { status?: number } }
        const status = Number(detail?.code ?? detail?.response?.status)
        if (taskId && (status === 404 || status === 410)) {
          analysisRequestId.current = ''
          setAnalysisError('原解析任务已结束，可重新解析参考图或手动填写。')
        } else if (taskId) {
          updateActiveLookup({ taskId, imageUrl })
          setAnalysisError('暂时无法获取解析进度，可重新查询或手动填写。')
        } else if (status === 409) {
          analysisRequestId.current = ''
          conflict = true
        } else {
          setAnalysisError(detail?.msg || detail?.message || '风格解析失败，请重试或手动填写。')
        }
      }
    } finally {
      if (current()) {
        activeAnalysis.current = false
        setAnalyzing(false)
        if (conflict) void checkActiveStyleAnalysis('已有任务刚刚结束，可重新解析这张参考图。')
      }
    }
  }

  function openCreateStyleModal() {
    resetAnalysis()
    replaceLocalCoverPreview(null)
    setManualMode(false)
    editedFields.current.clear()
    styleCoverUploadingRef.current = false
    setStyleCoverUploading(false)
    const next = {
      assetType: 'style',
      assetName: '',
      personalityDesc: '',
      promptText: '',
      imageUrl: '',
      remark: ''
    }
    styleFormRef.current = next
    setStyleForm(next)
    setStyleFormOpen(true)
    void checkActiveStyleAnalysis()
  }

  useImperativeHandle(ref, () => ({
    openCreateStyleModal,
    isCreatingStyle: () => creatingStyleRef.current
  }))

  function closeStyleFormModal() {
    resetAnalysis()
    replaceLocalCoverPreview(null)
    setStyleFormOpen(false)
    styleCoverUploadingRef.current = false
    setStyleCoverUploading(false)
    if (styleCoverInputRef.current) styleCoverInputRef.current.value = ''
  }

  function triggerStyleCoverUpload() {
    if (isCoverBusy()) return
    styleCoverInputRef.current?.click()
  }

  function clearStyleCover() {
    if (isCoverBusy()) return
    resetAnalysis()
    replaceLocalCoverPreview(null)
    const patch: Partial<typeof styleForm> = { imageUrl: '' }
    for (const key of ANALYSIS_FIELDS) {
      if (!editedFields.current.has(key)) patch[key] = ''
    }
    patchStyleForm(patch)
    if (styleCoverInputRef.current) styleCoverInputRef.current.value = ''
  }

  async function onStyleCoverFileChange(event: ChangeEvent<HTMLInputElement>) {
    const input = event.target
    const file = input.files?.[0]
    input.value = ''
    if (!file || isCoverBusy()) return

    if (!['image/jpeg', 'image/png'].includes(file.type)) {
      message.warning('请上传 JPG 或 PNG 图片')
      return
    }
    if (file.size > 10 * 1024 * 1024) {
      message.warning('图片大小不能超过 10MB')
      return
    }

    resetAnalysis()
    const generation = session.current
    styleCoverUploadingRef.current = true
    setStyleCoverUploading(true)
    const previewSrc = URL.createObjectURL(file)
    replaceLocalCoverPreview({ src: previewSrc })
    let uploadedUrl: string | null = null
    try {
      uploadedUrl = await uploadImageToOssWithToast(file, undefined, { showLoading: false })
      if (uploadedUrl && generation === session.current) {
        if (localCoverPreviewRef.current?.src === previewSrc) {
          replaceLocalCoverPreview({ src: previewSrc, uploadedUrl })
        }
        const patch: Partial<typeof styleForm> = { imageUrl: uploadedUrl }
        for (const key of ANALYSIS_FIELDS) {
          if (!editedFields.current.has(key)) patch[key] = ''
        }
        patchStyleForm(patch)
        if (!manualMode) void analyzeStyle(uploadedUrl)
      }
    } finally {
      if (generation === session.current) {
        if (!uploadedUrl) replaceLocalCoverPreview(null)
        styleCoverUploadingRef.current = false
        setStyleCoverUploading(false)
      }
    }
  }

  function validateStyleForm(): string | null {
    if (!styleFormRef.current.assetName.trim()) return '请填写资产名称'
    if (!styleFormRef.current.promptText.trim()) return '请填写风格提示词'
    return null
  }

  async function submitStyleForm() {
    if (creatingStyleRef.current || styleCoverUploadingRef.current) return
    if (styleLockedRef.current) {
      message.warning(styleLockActionMessageRef.current)
      closeStyleFormModal()
      return
    }
    const errText = validateStyleForm()
    if (errText) {
      message.warning(errText)
      return
    }
    creatingStyleRef.current = true
    setCreatingStyle(true)
    try {
      const payload = {
        assetType: 'style',
        assetName: styleFormRef.current.assetName.trim(),
        personalityDesc: styleFormRef.current.personalityDesc.trim() || undefined,
        promptText: styleFormRef.current.promptText.trim() || undefined,
        imageUrl: styleFormRef.current.imageUrl.trim() || undefined,
        remark: styleFormRef.current.remark.trim() || undefined
      }
      const created = await userAssetCustomCreate(payload)
      closeStyleFormModal()
      message.success('风格添加成功')

      await onCreated(created.id)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '添加风格失败')
    } finally {
      creatingStyleRef.current = false
      setCreatingStyle(false)
    }
  }

  const localPreviewSrc = localCoverPreview &&
    (!localCoverPreview.uploadedUrl || localCoverPreview.uploadedUrl === styleForm.imageUrl)
    ? localCoverPreview.src
    : ''
  const coverSrc = localPreviewSrc || styleForm.imageUrl
  const busyLabel = styleCoverUploading ? '正在上传参考图' : analyzing ? '正在提取风格特征'
    : activeLookup === 'checking' ? '正在查询解析任务' : ''
  const referenceState = busyLabel || waitingForAnalysis
    ? 'busy'
    : analysisError || activeLookup === 'error'
      ? 'error'
      : analysisComplete
        ? 'ready'
        : 'idle'
  const statusTitle = busyLabel || (waitingForAnalysis
    ? analysisError ? '解析进度暂不可用' : '解析仍在进行'
    : activeLookup === 'error' ? '暂时无法查询解析任务'
      : analysisError ? '参考图解析未完成' : analysisComplete ? '已填入风格建议' : manualMode ? '手动填写风格' : '上传风格参考图')
  const statusDescription = busyLabel
    ? '解析完成会补全空白字段，也可以直接填写名称和提示词。'
    : waitingForAnalysis || activeLookup === 'error'
      ? '可重新查询进度，也可以手动填写并保存。'
      : analysisComplete
        ? '保存前可以按需要调整名称、特征和提示词。'
        : manualMode
          ? '可上传 JPG 或 PNG 作为封面，再填写名称和提示词。'
          : '支持 JPG、PNG，最大 10MB。上传后自动提取色彩、光影和材质。'

  return (
    <Modal
      open={styleFormOpen}
      footer={null}
      centered
      width={760}
      className="style-analysis-modal"
      wrapClassName="create-flow-modal style-analysis-modal-wrap"
      onCancel={() => { if (!creatingStyleRef.current) closeStyleFormModal() }}
      title={<div className="style-analysis-heading"><strong>新增风格</strong><small>从参考图提炼色彩、光影和质感</small></div>}
    >
      <div className="asset-form-modal">
        <Form layout="vertical" disabled={creatingStyle} className="asset-form-modal__grid">
          <Form.Item className="asset-form-item asset-form-item--full asset-form-item--upload">
            <div className="style-reference" data-state={referenceState}>
              <input
                ref={styleCoverInputRef}
                type="file"
                accept="image/jpeg,image/png"
                hidden
                disabled={coverDisabled}
                onChange={onStyleCoverFileChange}
              />
              <button
                type="button"
                className="style-reference__cover"
                aria-label={coverSrc ? '更换风格图片' : '上传风格图片'}
                disabled={coverDisabled}
                onClick={triggerStyleCoverUpload}
              >
                {localPreviewSrc ? (
                  <img
                    className="style-reference__preview style-reference__image"
                    src={localPreviewSrc}
                    alt="风格图片"
                    onError={() => {
                      if (localCoverPreviewRef.current?.src === localPreviewSrc) replaceLocalCoverPreview(null)
                    }}
                  />
                ) : coverSrc ? (
                  <ShimmerImage
                    src={coverSrc}
                    alt="风格图片"
                    wrapperClass="style-reference__preview"
                    imgClass="style-reference__image"
                    objectFit="cover"
                    revealDirection="fade"
                    loadTimeoutMs={8000}
                    errorSlot={<span className="style-reference__placeholder">参考图不可预览</span>}
                  />
                ) : (
                  <span className="style-reference__placeholder">
                    <PictureOutlined aria-hidden />
                    <span>{styleCoverUploading ? '上传中…' : '上传图片'}</span>
                  </span>
                )}
                {analyzing && coverSrc ? <span className="style-reference__scan" aria-hidden /> : null}
              </button>
              <div className="style-reference__meta">
                <div role="status" className="style-reference__status">
                  <span className="style-reference__label">
                    {analysisComplete ? <CheckCircleOutlined aria-hidden /> : <ScanOutlined aria-hidden />}
                    参考图解析
                  </span>
                  <strong>{statusTitle}</strong>
                  <p>{statusDescription}</p>
                  {busyLabel ? <div className="style-reference__activity" aria-hidden><span /></div> : null}
                </div>
                <div className="style-reference__actions">
                  {waitingForAnalysis && <button type="button" className="style-reference__action is-primary" onClick={resumeStyleAnalysis}>查询进度</button>}
                  {activeLookup === 'error' && <button type="button" className="style-reference__action is-primary" onClick={() => void checkActiveStyleAnalysis()}>重试查询</button>}
                  {analysisError && activeLookup === 'idle' && styleForm.imageUrl && <button type="button" className="style-reference__action is-primary" disabled={creatingStyle} onClick={() => void analyzeStyle(styleForm.imageUrl)}>重新解析</button>}
                  {!manualMode && !styleCoverUploading && (analyzing || activeLookup !== 'idle' || analysisError) && (
                    <button type="button" className="style-reference__action" disabled={creatingStyle} onClick={() => { resetAnalysis(); setManualMode(true) }}>手动填写</button>
                  )}
                  {styleForm.imageUrl && !coverDisabled && <button type="button" className="style-reference__action" onClick={clearStyleCover}>移除图片</button>}
                </div>
              </div>
            </div>
          </Form.Item>
          <Form.Item label="资产类型" required className="asset-form-item asset-form-item--half">
            <Select
              value={styleForm.assetType}
              disabled
              placeholder="类型"
              options={styleTypeSelectOptions}
            />
          </Form.Item>
          <Form.Item label="资产名称" required className="asset-form-item asset-form-item--half">
            <Input
              value={styleForm.assetName}
              maxLength={100}
              placeholder="请输入"
              onChange={(e) => editStyleField('assetName', e.target.value)}
            />
          </Form.Item>
          <Form.Item label="特征描述" className="asset-form-item asset-form-item--full">
            <Input.TextArea
              value={styleForm.personalityDesc}
              rows={3}
              maxLength={500}
              placeholder="请输入"
              onChange={(e) => editStyleField('personalityDesc', e.target.value)}
            />
          </Form.Item>
          <Form.Item label="提示词" required className="asset-form-item asset-form-item--full">
            <Input.TextArea
              value={styleForm.promptText}
              maxLength={4000}
              rows={3}
              placeholder="请输入用于生成画面的风格提示词"
              onChange={(e) => editStyleField('promptText', e.target.value)}
            />
          </Form.Item>
          <Form.Item label="备注" className="asset-form-item asset-form-item--full">
            <Input
              value={styleForm.remark}
              maxLength={500}
              placeholder="请输入"
              onChange={(e) => editStyleField('remark', e.target.value)}
            />
          </Form.Item>
        </Form>

        <div className="asset-form-modal__footer">
          <Button disabled={creatingStyle} onClick={closeStyleFormModal}>
            <div className="text-gradient">取消</div>
          </Button>
          <Button type="primary" disabled={styleCoverUploading} loading={creatingStyle} onClick={submitStyleForm}>
            保存风格
          </Button>
        </div>
      </div>
    </Modal>
  )
})

export default GlobalSettingStyleFormModal
