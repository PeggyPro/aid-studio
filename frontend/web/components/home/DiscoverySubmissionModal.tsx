'use client'

/* eslint-disable @next/next/no-img-element -- OSS 动态媒体地址无法使用静态 image loader。 */

import { CloseOutlined, UploadOutlined } from '@ant-design/icons'
import { Button, Form, Input, Modal, message } from 'antd'
import type { ChangeEvent, MouseEvent as ReactMouseEvent } from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { PreviewableImageThumb } from '~/components/common/PreviewableImageThumb'
import { loadPublicConfig } from '~/composables/useAuthPublicConfig'
import deleteWhiteIconRaw from '~/assets/img/home/delete-white.svg'
import type { UserDiscoveryItem } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import {
  userDiscoveryDetail,
  userDiscoveryResubmit,
  userDiscoverySubmit
} from '~/utils/businessApi'
import { readDiscoveryVideoDimensions } from '~/utils/discoveryVideoMetadata'
import {
  readDiscoveryImageDimensions,
  readDiscoveryImageUrlDimensions
} from '~/utils/discoveryImageMetadata'
import {
  buildDiscoveryResubmission,
  buildNewDiscoverySubmission,
  validateDiscoverySubmissionMedia
} from '~/utils/discoverySubmissionPayload'
import {
  attemptDiscoveryCoverReplacement,
  completeDiscoveryVideoReplacement,
  preserveDiscoveryResubmitVideo
} from '~/utils/discoveryResubmitMedia'
import {
  fileMatchesUploadKind,
  resolveVisibleUploadRule,
  type VisibleUploadRule
} from '~/utils/discoveryUploadRules'
import { uploadImageToOssWithToast, uploadVideoToOssWithToast } from '~/utils/ossUpload'
import './DiscoverySubmissionModal.css'

const deleteWhiteIcon = assetUrl(deleteWhiteIconRaw)

interface SubmitFormValues {
  name: string
  description: string
}

interface UploadState {
  coverUrl: string
  coverWidth: number
  coverHeight: number
  mediaUrl: string
  mediaWidth: number
  mediaHeight: number
}

const EMPTY_UPLOAD: UploadState = {
  coverUrl: '',
  coverWidth: 0,
  coverHeight: 0,
  mediaUrl: '',
  mediaWidth: 0,
  mediaHeight: 0
}

export interface DiscoverySubmissionModalProps {
  open: boolean
  /** 编辑时传入投稿 id，弹窗打开后会请求详情回显 */
  editingId?: number | null
  onOpenChange: (open: boolean) => void
  /** 新建或重新提交成功后回调 */
  onSuccess?: () => void
}

function errorText(error: unknown, fallback: string) {
  const value = error as { msg?: string; message?: string }
  return String(value?.msg || value?.message || fallback)
}

export default function DiscoverySubmissionModal({
  open,
  editingId = null,
  onOpenChange,
  onSuccess
}: DiscoverySubmissionModalProps) {
  const [form] = Form.useForm<SubmitFormValues>()
  const [resolvedEditingId, setResolvedEditingId] = useState<number | null>(null)
  const [editingPreviewVideoUrl, setEditingPreviewVideoUrl] = useState<string | null>(null)
  const [upload, setUpload] = useState<UploadState>(EMPTY_UPLOAD)
  const [coverError, setCoverError] = useState('')
  const [videoError, setVideoError] = useState('')
  const [coverUploading, setCoverUploading] = useState(false)
  const [videoUploading, setVideoUploading] = useState(false)
  const [coverProgress, setCoverProgress] = useState(0)
  const [videoProgress, setVideoProgress] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const [hydrating, setHydrating] = useState(false)
  const coverInputRef = useRef<HTMLInputElement | null>(null)
  const videoInputRef = useRef<HTMLInputElement | null>(null)
  const coverButtonRef = useRef<HTMLButtonElement | null>(null)
  const videoButtonRef = useRef<HTMLButtonElement | null>(null)
  const [coverUploadRule, setCoverUploadRule] = useState<VisibleUploadRule>({
    accept: 'image/*',
    text: '正在读取图片格式与大小限制…',
    extensions: [],
    available: false,
    configured: false
  })
  const [videoUploadRule, setVideoUploadRule] = useState<VisibleUploadRule>({
    accept: 'video/*',
    text: '正在读取视频格式与大小限制…',
    extensions: [],
    available: false,
    configured: false
  })

  const busy =
    hydrating || coverUploading || videoUploading || submitting

  const resetFormState = useCallback(() => {
    setResolvedEditingId(null)
    setEditingPreviewVideoUrl(null)
    setUpload(EMPTY_UPLOAD)
    setCoverError('')
    setVideoError('')
    setCoverProgress(0)
    setVideoProgress(0)
    if (coverInputRef.current) coverInputRef.current.value = ''
    if (videoInputRef.current) videoInputRef.current.value = ''
  }, [])

  const resetForm = useCallback(() => {
    resetFormState()
    form.resetFields()
  }, [form, resetFormState])

  const hydrateFromItem = useCallback(
    (item: UserDiscoveryItem) => {
      const videoState = item.mediaType === 'video'
        ? preserveDiscoveryResubmitVideo(item)
        : { mediaUrl: '', mediaWidth: 0, mediaHeight: 0, previewVideoUrl: null }
      setResolvedEditingId(item.id)
      setEditingPreviewVideoUrl(videoState.previewVideoUrl)
      form.setFieldsValue({ name: item.name, description: item.description })
      setUpload({
        coverUrl: item.coverUrl,
        coverWidth: item.mediaType === 'image' ? item.mediaWidth : 0,
        coverHeight: item.mediaType === 'image' ? item.mediaHeight : 0,
        mediaUrl: videoState.mediaUrl,
        mediaWidth: videoState.mediaWidth,
        mediaHeight: videoState.mediaHeight
      })
      setCoverError('')
      setVideoError('')
      setCoverProgress(item.coverUrl ? 100 : 0)
      setVideoProgress(item.mediaUrl ? 100 : 0)
      if (item.mediaType === 'video' && item.coverUrl) {
        void readDiscoveryImageUrlDimensions(item.coverUrl)
          .then((dimensions) => {
            setUpload((current) => current.coverUrl === item.coverUrl
              ? {
                  ...current,
                  coverWidth: dimensions.mediaWidth,
                  coverHeight: dimensions.mediaHeight
                }
              : current)
          })
          .catch(() => undefined)
      }
    },
    [form]
  )

  useEffect(() => {
    if (!open) {
      resetFormState()
      return
    }

    let cancelled = false
    const targetId = editingId != null && Number.isFinite(editingId) && editingId > 0 ? editingId : null

    if (targetId == null) {
      resetForm()
      return
    }

    setHydrating(true)
    void userDiscoveryDetail({ id: targetId })
      .then((detail) => {
        if (cancelled) return
        hydrateFromItem(detail)
      })
      .catch((error) => {
        if (cancelled) return
        message.error(errorText(error, '投稿详情加载失败，请重试'))
        onOpenChange(false)
      })
      .finally(() => {
        if (!cancelled) setHydrating(false)
      })

    return () => {
      cancelled = true
    }
  }, [editingId, hydrateFromItem, onOpenChange, open, resetForm, resetFormState])

  useEffect(() => {
    if (!open) return
    let cancelled = false
    void loadPublicConfig()
      .then((config) => {
        if (cancelled) return
        if (!config?.upload) {
          setCoverUploadRule(resolveVisibleUploadRule(null, 'image'))
          setVideoUploadRule(resolveVisibleUploadRule(null, 'video'))
          return
        }
        setCoverUploadRule(resolveVisibleUploadRule(config.upload, 'image'))
        setVideoUploadRule(resolveVisibleUploadRule(config.upload, 'video'))
      })
      .catch(() => {
        if (cancelled) return
        setCoverUploadRule(resolveVisibleUploadRule(null, 'image'))
        setVideoUploadRule(resolveVisibleUploadRule(null, 'video'))
      })
    return () => {
      cancelled = true
    }
  }, [open])

  async function handleCoverFile(event: ChangeEvent<HTMLInputElement>) {
    const input = event.target
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    if (!coverUploadRule.available) {
      setCoverError(coverUploadRule.text)
      return
    }
    if (!fileMatchesUploadKind(file, 'image', coverUploadRule)) {
      setCoverError('请选择图片文件')
      coverButtonRef.current?.focus()
      return
    }
    const previousCover = {
      coverUrl: upload.coverUrl,
      coverWidth: upload.coverWidth,
      coverHeight: upload.coverHeight
    }
    const restorePreviousCover = () => {
      setUpload((current) => ({ ...current, ...previousCover }))
    }
    setCoverUploading(true)
    setCoverProgress(0)
    setCoverError('')
    try {
      let dimensions = { mediaWidth: 0, mediaHeight: 0 }
      const replacement = await attemptDiscoveryCoverReplacement(
        previousCover.coverUrl,
        async () => {
          dimensions = await readDiscoveryImageDimensions(file)
          return uploadImageToOssWithToast(file, setCoverProgress)
        }
      )
      if (!replacement.replaced) {
        restorePreviousCover()
        const text = replacement.error
          ? errorText(replacement.error, '封面上传失败，请重新选择')
          : '封面上传失败，请重新选择'
        setCoverError(text)
        if (replacement.error) message.error(text)
        return
      }
      setUpload((current) => ({
        ...current,
        coverUrl: replacement.coverUrl,
        coverWidth: dimensions.mediaWidth,
        coverHeight: dimensions.mediaHeight
      }))
      if (!upload.mediaUrl) setVideoError('')
    } finally {
      setCoverUploading(false)
    }
  }

  async function handleVideoFile(event: ChangeEvent<HTMLInputElement>) {
    const input = event.target
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    if (!videoUploadRule.available) {
      setVideoError(videoUploadRule.text)
      return
    }
    if (!fileMatchesUploadKind(file, 'video', videoUploadRule)) {
      setVideoError('请选择视频文件')
      videoButtonRef.current?.focus()
      return
    }
    const previousVideo = {
      mediaUrl: upload.mediaUrl,
      mediaWidth: upload.mediaWidth,
      mediaHeight: upload.mediaHeight,
      previewVideoUrl: editingPreviewVideoUrl
    }
    const restorePreviousVideo = () => {
      setUpload((current) => ({
        ...current,
        mediaUrl: previousVideo.mediaUrl,
        mediaWidth: previousVideo.mediaWidth,
        mediaHeight: previousVideo.mediaHeight
      }))
      setEditingPreviewVideoUrl(previousVideo.previewVideoUrl)
    }
    setVideoUploading(true)
    setVideoProgress(0)
    setVideoError('')
    setUpload((current) => ({ ...current, mediaUrl: '', mediaWidth: 0, mediaHeight: 0 }))
    try {
      const dimensions = await readDiscoveryVideoDimensions(file)
      const url = await uploadVideoToOssWithToast(file, setVideoProgress)
      if (!url) {
        setVideoError('视频上传失败，请重新选择')
        restorePreviousVideo()
        return
      }
      const replacement = completeDiscoveryVideoReplacement(url, dimensions)
      setUpload((current) => ({
        ...current,
        mediaUrl: replacement.mediaUrl,
        mediaWidth: replacement.mediaWidth,
        mediaHeight: replacement.mediaHeight
      }))
      setEditingPreviewVideoUrl(replacement.previewVideoUrl)
      if (!upload.coverUrl) setCoverError('')
    } catch (error) {
      const text = errorText(error, '视频信息读取失败，请重新选择')
      setVideoError(text)
      restorePreviousVideo()
      message.error(text)
    } finally {
      setVideoUploading(false)
    }
  }

  async function submit() {
    let values: SubmitFormValues
    try {
      values = await form.validateFields()
    } catch {
      return
    }

    const hasVideo = Boolean(upload.mediaUrl.trim())
    const submissionMedia = {
      coverUrl: upload.coverUrl,
      mediaUrl: upload.mediaUrl,
      mediaWidth: hasVideo ? upload.mediaWidth : upload.coverWidth,
      mediaHeight: hasVideo ? upload.mediaHeight : upload.coverHeight
    }
    const mediaValidation = validateDiscoverySubmissionMedia(submissionMedia)
    setCoverError(mediaValidation.coverError)
    setVideoError(mediaValidation.videoError)
    if (!mediaValidation.valid) {
      if (mediaValidation.focus === 'cover') {
        const target = coverUploadRule.available ? coverButtonRef : videoButtonRef
        target.current?.focus()
      } else if (mediaValidation.focus === 'video') {
        videoButtonRef.current?.focus()
      }
      return
    }
    if (coverUploading || videoUploading) {
      message.warning('请等待文件上传完成')
      return
    }

    const fields = {
      name: values.name.trim(),
      description: values.description.trim(),
      ...submissionMedia
    }
    setSubmitting(true)
    try {
      if (resolvedEditingId == null) {
        await userDiscoverySubmit(buildNewDiscoverySubmission(fields))
      } else {
        await userDiscoveryResubmit(
          buildDiscoveryResubmission(resolvedEditingId, fields, editingPreviewVideoUrl)
        )
      }
      message.success(resolvedEditingId == null ? '投稿已提交审核' : '已重新提交审核')
      onOpenChange(false)
      onSuccess?.()
    } catch (error) {
      message.error(
        errorText(
          error,
          resolvedEditingId == null ? '投稿失败，请重试' : '重新投稿失败，请重试'
        )
      )
    } finally {
      setSubmitting(false)
    }
  }

  function handleCancel() {
    if (busy) return
    onOpenChange(false)
  }

  function clearCover(event: ReactMouseEvent) {
    event.stopPropagation()
    if (busy || coverUploading) return
    setUpload((current) => ({ ...current, coverUrl: '', coverWidth: 0, coverHeight: 0 }))
    setCoverError('')
  }

  function clearVideo(event: ReactMouseEvent) {
    event.stopPropagation()
    if (busy || videoUploading) return
    setUpload((current) => ({
      ...current,
      mediaUrl: '',
      mediaWidth: 0,
      mediaHeight: 0
    }))
    setEditingPreviewVideoUrl(null)
    setVideoError('')
  }

  const modalTitle = resolvedEditingId == null ? '投稿作品' : '编辑投稿'

  return (
    <Modal
      open={open}
      footer={null}
      closable={false}
      centered
      destroyOnHidden
      width={1100}
      mask={{ closable: !busy }}
      wrapClassName="create-flow-modal discovery-submission-modal-wrap"
      className="discovery-submission-modal"
      onCancel={handleCancel}
    >
      <div className="discovery-submission-modal__shell">
        <button
          type="button"
          className={`discovery-submission-modal__close${busy ? ' is-disabled' : ''}`}
          aria-label="关闭"
          disabled={busy}
          onClick={handleCancel}
        >
          <CloseOutlined />
        </button>

        <div className="discovery-submission-modal__title">
          <ModalTitleWatermark title={modalTitle} watermark="SUBMIT" />
        </div>

        <div className="discovery-submission-modal__body">
          <Form
            form={form}
            layout="vertical"
            requiredMark={false}
            className="discovery-submission-modal__form"
            scrollToFirstError={{ behavior: 'smooth', block: 'center', focus: true }}
            initialValues={{ name: '', description: '' }}
          >
            {hydrating ? (
              <div className="discovery-submission-modal__loading">加载中…</div>
            ) : (
              <>
            <div className="discovery-submission-modal__fields">
              <Form.Item
                name="name"
                label="作品名称"
                className="discovery-submission-field-item"
                rules={[
                  { required: true, whitespace: true, message: '请填写作品名称' },
                  { max: 100, message: '作品名称不能超过 100 个字符' }
                ]}
              >
                <Input
                  autoFocus
                  className="discovery-submission-modal__control"
                  placeholder="请输入"
                  maxLength={100}
                  showCount
                  variant="borderless"
                />
              </Form.Item>
              <Form.Item
                name="description"
                label="作品描述"
                className="discovery-submission-field-item discovery-submission-field-item--textarea"
                rules={[
                  { required: true, whitespace: true, message: '请填写作品描述' },
                  { max: 1000, message: '作品描述不能超过 1000 个字符' }
                ]}
              >
                <Input.TextArea
                  className="discovery-submission-modal__control"
                  rows={3}
                  placeholder="请输入"
                  maxLength={1000}
                  showCount
                  variant="borderless"
                />
              </Form.Item>
            </div>

            <div className="discovery-submission-modal__media-grid">
              <section className="discovery-submission-upload" aria-label="上传封面">
                <input
                  ref={coverInputRef}
                  type="file"
                  accept={coverUploadRule.accept}
                  disabled={!coverUploadRule.available}
                  hidden
                  onChange={handleCoverFile}
                />
                {upload.coverUrl ? (
                  <div
                    className={`discovery-submission-upload__box discovery-submission-upload__box--preview${coverError ? ' is-error' : ''}${coverUploading ? ' is-uploading' : ''}`}
                    aria-busy={coverUploading}
                  >
                    <PreviewableImageThumb
                      src={upload.coverUrl}
                      alt="投稿封面预览"
                      title="投稿封面"
                      objectFit="contain"
                    />
                    <button
                      type="button"
                      className="discovery-submission-upload__delete"
                      disabled={busy || coverUploading}
                      aria-label="删除封面"
                      title="删除封面"
                      onClick={clearCover}
                    >
                      <img src={deleteWhiteIcon} alt="" width={14} height={14} />
                    </button>
                    {coverUploading ? (
                      <span className="discovery-submission-upload__progress">{coverProgress}%</span>
                    ) : null}
                  </div>
                ) : (
                  <button
                    ref={coverButtonRef}
                    type="button"
                    className={`discovery-submission-upload__box${coverError ? ' is-error' : ''}${coverUploading ? ' is-uploading' : ''}`}
                    disabled={coverUploading || !coverUploadRule.available}
                    aria-busy={coverUploading}
                    aria-describedby={
                      coverError ? 'discovery-cover-error' : 'discovery-cover-hint'
                    }
                    onClick={() => coverInputRef.current?.click()}
                  >
                    <span className="discovery-submission-upload__placeholder">
                      <span className="discovery-submission-upload__title-row">
                        <UploadOutlined className="discovery-submission-upload__icon" />
                        <span>上传封面</span>
                      </span>
                      <span id="discovery-cover-hint" className="discovery-submission-upload__hint">
                        {coverUploadRule.text}
                      </span>
                    </span>
                    {coverUploading ? (
                      <span className="discovery-submission-upload__progress">{coverProgress}%</span>
                    ) : null}
                  </button>
                )}
                {coverError ? (
                  <p id="discovery-cover-error" className="discovery-submission-upload__error">
                    {coverError}
                  </p>
                ) : null}
              </section>

              <section className="discovery-submission-upload" aria-label="上传视频">
                <input
                  ref={videoInputRef}
                  type="file"
                  accept={videoUploadRule.accept}
                  disabled={!videoUploadRule.available}
                  hidden
                  onChange={handleVideoFile}
                />
                {upload.mediaUrl ? (
                  <div
                    className={`discovery-submission-upload__box discovery-submission-upload__box--preview${videoError ? ' is-error' : ''}${videoUploading ? ' is-uploading' : ''}`}
                    aria-busy={videoUploading}
                  >
                    <video
                      className="discovery-submission-upload__media"
                      src={upload.mediaUrl}
                      controls
                      playsInline
                      preload="metadata"
                      aria-label="投稿视频预览"
                      onClick={(event) => event.stopPropagation()}
                    />
                    <button
                      type="button"
                      className="discovery-submission-upload__delete"
                      disabled={busy || videoUploading}
                      aria-label="删除视频"
                      title="删除视频"
                      onClick={clearVideo}
                    >
                      <img src={deleteWhiteIcon} alt="" width={14} height={14} />
                    </button>
                    {videoUploading ? (
                      <span className="discovery-submission-upload__progress">{videoProgress}%</span>
                    ) : null}
                  </div>
                ) : (
                  <button
                    ref={videoButtonRef}
                    type="button"
                    className={`discovery-submission-upload__box${videoError ? ' is-error' : ''}${videoUploading ? ' is-uploading' : ''}`}
                    disabled={videoUploading || !videoUploadRule.available}
                    aria-busy={videoUploading}
                    aria-describedby={
                      videoError ? 'discovery-video-error' : 'discovery-video-hint'
                    }
                    onClick={() => videoInputRef.current?.click()}
                  >
                    <span className="discovery-submission-upload__placeholder">
                      <span className="discovery-submission-upload__title-row">
                        <UploadOutlined className="discovery-submission-upload__icon" />
                        <span>上传视频</span>
                      </span>
                      <span id="discovery-video-hint" className="discovery-submission-upload__hint">
                        {videoUploadRule.text}
                      </span>
                    </span>
                    {videoUploading ? (
                      <span className="discovery-submission-upload__progress">{videoProgress}%</span>
                    ) : null}
                  </button>
                )}
                {videoError ? (
                  <p id="discovery-video-error" className="discovery-submission-upload__error">
                    {videoError}
                  </p>
                ) : null}
              </section>
            </div>
              </>
            )}
          </Form>
        </div>

        <div className="discovery-submission-modal__footer">
          <p className="discovery-submission-modal__disclaimer">
            <span className="discovery-submission-modal__disclaimer-mark">*</span>
            请投稿自有版权或已获得完整授权的内容，作品审核通过后将公开展示于发现板块，供AID用户浏览使用。投稿者对作品版权负全部责任，如发生侵权纠纷，本平台不承担相关责任。
          </p>
          <div className="discovery-submission-modal__actions">
            <Button
              className="discovery-submission-modal__btn-cancel"
              disabled={busy}
              onClick={handleCancel}
            >
              取消
            </Button>
            <Button
              type="primary"
              className="discovery-submission-modal__btn-submit"
              loading={submitting}
              disabled={busy && !submitting}
              onClick={() => void submit()}
            >
              提交
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  )
}
