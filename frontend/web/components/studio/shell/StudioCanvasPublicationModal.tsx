'use client'

import { useEffect, useRef, useState } from 'react'
import { Button, Input, Modal, Switch, message } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import type { CanvasPublicationState } from '@/types/canvas-api'
import { useStudioUiStore } from '@/stores/studioUi'
import { ApiBusinessError, apiErrorMessage } from '@/utils/apiError'
import {
  canvasProjectUpdate,
  canvasPublicationPrepare,
  canvasPublicationScenesRead,
  canvasPublicationSubmit,
} from '@/utils/business/canvas'
import {
  canvasPublicationChange,
  canvasPublicationStatus,
  canvasPublicationVersions,
} from '@/utils/canvas/canvasPublicationApi'
import { canvasPublicationDraft } from '@/utils/canvas/canvasPublicationDraft'
import {
  canvasResourceDisplayUrl,
  createCanvasResourceAccess,
} from '@/utils/canvas/canvasPublicationFiles'
import { canvasOperationId } from '@/utils/canvas/canvasId'
import { canvasQuoteIdentity } from '@/utils/canvas/canvasQuote'
import {
  canvasPublicationMediaNodes,
  canvasPublicationSourceKey,
} from '@/utils/canvas/canvasPublicationMedia'
import {
  canvasPublicationSubmission,
  type CanvasPublicationPendingSubmission,
} from '@/utils/canvas/canvasPublicationSubmission'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioWorkspaceModalProvider } from './StudioWorkspaceModalProvider'
import {
  StudioCanvasPublicationMediaPicker,
  type CanvasPublicationMediaSelection,
} from './StudioCanvasPublicationMediaPicker'
import { StudioPublicationHistory } from './StudioPublicationHistory'
import { PublicationSummary } from './StudioPublicationStatus'
import { PublicationFormFeedback } from '@/components/common/publication/PublicationFormFeedback'
import './studio-canvas-publication.css'

type Props = {
  open: boolean
  runtime: CanvasProjectWorkspaceRuntime
  onOpenChange: (open: boolean) => void
  onSuccess?: () => void
}

export default function StudioCanvasPublicationModal(props: Props) {
  const scope = useStudioUiStore((state) => state.scopeKey)
  return (
    <StudioWorkspaceModalProvider>
      {props.open && props.runtime.project && (
        <PublicationForm
          key={`${props.runtime.project.projectId}:${scope}`}
          {...props}
        />
      )}
    </StudioWorkspaceModalProvider>
  )
}

function PublicationForm({ runtime, onOpenChange, onSuccess }: Props) {
  const project = runtime.project!
  const [restored] = useState(() =>
    canvasPublicationSubmission.read(project.ownerUserId, project.projectId),
  )
  const [draft] = useState(() =>
    canvasPublicationDraft.read(project.ownerUserId, project.projectId),
  )
  const [video, setVideo] = useState<CanvasPublicationMediaSelection | null>(
    restored ? { resource: restored.video } : draft?.video || null,
  )
  const [cover, setCover] = useState<CanvasPublicationMediaSelection | null>(
    restored ? { resource: restored.cover } : draft?.cover || null,
  )
  const [description, setDescription] = useState(
    restored?.request.projectDesc ??
      draft?.description ??
      (project.projectDesc || '').slice(0, 500),
  )
  const [projectName, setProjectName] = useState(
    restored?.request.projectName ?? draft?.projectName ?? project.projectName,
  )
  const [publicCanvas, setPublicCanvas] = useState(
    restored ? true : (draft?.publicCanvas ?? false),
  )
  const [allowCopy, setAllowCopy] = useState(
    restored?.request.allowCopy ?? draft?.allowCopy ?? false,
  )
  const [confirmation, confirmationContext] = Modal.useModal()
  const [feedback, feedbackContext] = message.useMessage()
  const [state, setState] = useState<CanvasPublicationState | null>(null)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [picker, setPicker] = useState<'VIDEO' | 'IMAGE' | null>(null)
  const [error, setError] = useState('')
  const [pending, setPending] =
    useState<CanvasPublicationPendingSubmission | null>(restored)
  const [versions, setVersions] = useState<Awaited<
    ReturnType<typeof canvasPublicationVersions>
  > | null>(null)
  const [versionsLoading, setVersionsLoading] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [historyError, setHistoryError] = useState('')
  const versionsLock = useRef(false)
  const lock = useRef(false)
  const sequence = useRef(0)
  const active = useRef(true)
  const identity = useRef(canvasQuoteIdentity()).current
  const scope = useRef(useStudioUiStore.getState().scopeKey).current
  const current = () =>
    active.current &&
    canvasQuoteIdentity() === identity &&
    useStudioUiStore.getState().scopeKey === scope

  function reportError(text: string) {
    setError(text)
    void feedback.error({ key: 'canvas-publication-error', content: text, duration: 6 })
  }

  async function load() {
    const seq = ++sequence.current
    try {
      const prepared = await canvasPublicationStatus(project.projectId)
      if (!current() || seq !== sequence.current) return
      setState(prepared)
    } catch (error) {
      if (current() && seq === sequence.current) {
        setState(null)
        setError(apiErrorMessage(error, '加载发布信息失败，请重试'))
      }
    } finally {
      if (current() && seq === sequence.current) setLoading(false)
    }
  }

  useEffect(() => {
    active.current = true
    void load()
    return () => {
      active.current = false
    }
    // The form is keyed by project and scope; edits must not be reset by background refreshes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const access = createCanvasResourceAccess()
    let cancelled = false
    void access
      .prepare(
        [video?.resource, cover?.resource].filter((resource) => !!resource),
      )
      .then((resources) => {
        if (cancelled || !current()) return
        const byId = new Map(
          resources.map((resource) => [resource.resourceId, resource]),
        )
        setVideo((previous) =>
          previous && byId.has(previous.resource.resourceId)
            ? { ...previous, resource: byId.get(previous.resource.resourceId)! }
            : previous,
        )
        setCover((previous) =>
          previous && byId.has(previous.resource.resourceId)
            ? { ...previous, resource: byId.get(previous.resource.resourceId)! }
            : previous,
        )
      })
      .catch((error) => {
        if (!cancelled && current())
          setError(apiErrorMessage(error, '发布素材预览读取失败'))
      })
    return () => {
      cancelled = true
      access.dispose()
    }
    // Display URLs belong to this selection and must not retrigger their own loading.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    video?.resource.resourceId,
    video?.resource.resourceUrl,
    cover?.resource.resourceId,
    cover?.resource.resourceUrl,
  ])

  function validateSource(
    selection: CanvasPublicationMediaSelection,
    type: 'VIDEO' | 'IMAGE',
  ) {
    if (!selection.nodeId) return
    const node = canvasPublicationMediaNodes(
      useStudioUiStore.getState().nodes,
      type,
    ).find((node) => node.id === selection.nodeId)
    if (!node || canvasPublicationSourceKey(node) !== selection.sourceKey)
      throw new Error(
        `所选${type === 'VIDEO' ? '视频' : '封面'}已变化或被删除，请重新选择`,
      )
  }

  async function submit() {
    if (lock.current || loading || picker) return
    if (!current()) {
      reportError('画布或登录身份已变化，请重新打开发布面板')
      return
    }
    if (runtime.role !== 'OWNER') {
      reportError('只有画布所有者可以发布')
      return
    }
    if (projectName.trim().length > 100 || description.trim().length > 500) {
      reportError('发布名称最多100字，作品描述最多500字')
      return
    }
    if (!pending && publicCanvas && (!video || !cover || !description.trim())) {
      reportError(
        !video
          ? '请选择作品视频'
          : !cover
            ? '请选择作品封面'
            : '请填写作品描述',
      )
      return
    }
    lock.current = true
    setSubmitting(true)
    setError('')
    let sending: CanvasPublicationPendingSubmission | null = pending
    try {
      if (!sending && !publicCanvas) {
        if (video) validateSource(video, 'VIDEO')
        if (cover) validateSource(cover, 'IMAGE')
        await runtime.saveNow()
        if (!current()) return
        await canvasProjectUpdate({
          projectId: project.projectId,
          projectDesc: description.trim(),
          ...(cover ? { coverUrl: cover.resource.resourceUrl } : {}),
        })
        if (!current()) return
        if (video) validateSource(video, 'VIDEO')
        if (cover) validateSource(cover, 'IMAGE')
        canvasPublicationDraft.write(project.ownerUserId, project.projectId, {
          projectName,
          description,
          publicCanvas: false,
          allowCopy,
          video,
          cover,
        })
        message.success('画布已保存；发布表单草稿已保存在当前浏览器')
        onOpenChange(false)
        onSuccess?.()
        return
      }
      if (!sending) {
        validateSource(video!, 'VIDEO')
        validateSource(cover!, 'IMAGE')
        await runtime.saveNow()
        if (!current()) return
        const { nodes, directorScenes } = useStudioUiStore.getState()
        if (nodes.some(({ data }) => !data.canvasTransient && data.kind === 'director'
          && (!data.directorSceneId || !Object.hasOwn(directorScenes, data.directorSceneId)))) {
          throw new Error('画布含尚未恢复的3D预演场景，恢复原始场景或移除对应节点后再发布')
        }
        const prepared = await canvasPublicationPrepare(project.projectId)
        if (!current()) return
        setState(prepared)
        if (!prepared.storageReady)
          throw new Error('作品存储尚未就绪，请稍后重试')
        if (prepared.pendingId)
          throw new Error('已有待审核版本，请等待审核后再提交')
        const scenes = await canvasPublicationScenesRead(project.projectId)
        if (!current()) return
        validateSource(video!, 'VIDEO')
        validateSource(cover!, 'IMAGE')
        sending = {
          request: {
            projectId: project.projectId,
            operationId: canvasOperationId('publication'),
            expectedRevision: prepared.draftRevision,
            expectedSceneVersion: scenes.sceneVersion,
            projectName: projectName.trim(),
            projectDesc: description.trim(),
            videoResourceId: video!.resource.resourceId,
            coverResourceId: cover!.resource.resourceId,
            publicCanvas: true,
            allowCopy,
          },
          video: video!.resource,
          cover: cover!.resource,
        }
        canvasPublicationSubmission.write(project.ownerUserId, sending)
        setPending(sending)
      }
      if (pending) {
        const latest = await canvasPublicationStatus(project.projectId)
        if (!current()) return
        setState(latest)
      }
      const result = await canvasPublicationSubmit(sending.request)
      if (!current()) return
      if (!result.publicationId) throw new Error('提交结果不完整，请重试核实')
      canvasPublicationSubmission.clear(project.ownerUserId, project.projectId)
      try {
        canvasPublicationDraft.write(project.ownerUserId, project.projectId, {
          projectName: sending.request.projectName ?? project.projectName,
          description: sending.request.projectDesc,
          publicCanvas: true,
          allowCopy: sending.request.allowCopy,
          video: { resource: sending.video },
          cover: { resource: sending.cover },
        })
      } catch {
        // Browser storage failure must not turn an accepted submission into an apparent failure.
      }
      setPending(null)
      message.success('作品已提交审核，审核通过后公开展示')
      onOpenChange(false)
      onSuccess?.()
    } catch (error) {
      if (!current()) return
      if (sending && error instanceof ApiBusinessError) {
        canvasPublicationSubmission.clear(
          project.ownerUserId,
          project.projectId,
        )
        setPending(null)
        void load()
      }
      reportError(
        apiErrorMessage(
          error,
          sending ? '提交结果暂未确认，请重试核实原请求' : '提交失败，请重试',
        ),
      )
    } finally {
      lock.current = false
      if (current()) setSubmitting(false)
    }
  }

  async function change(action: 'WITHDRAW' | 'DISABLE_COPY' | 'CANCEL_REVIEW') {
    if (!state || lock.current || !current() || runtime.role !== 'OWNER') return
    lock.current = true
    setSubmitting(true)
    setError('')
    try {
      const next = await canvasPublicationChange(
        project.projectId,
        state.stateVersion,
        action,
      )
      if (current()) {
        setState(next)
        onSuccess?.()
        message.success(
          action === 'WITHDRAW'
            ? '作品已撤回，工程与媒体均停止公开'
            : action === 'DISABLE_COPY'
              ? '已关闭复制权限'
              : '已取消待审核版本',
        )
      }
    } catch (e) {
      if (current()) {
        setError(apiErrorMessage(e, '发布状态更新失败'))
        try {
          const latest = await canvasPublicationStatus(project.projectId)
          if (current()) setState(latest)
        } catch {}
      }
    } finally {
      lock.current = false
      if (current()) setSubmitting(false)
    }
  }
  const confirmChange = (
    action: 'WITHDRAW' | 'DISABLE_COPY' | 'CANCEL_REVIEW',
  ) =>
    confirmation.confirm({
      title:
        action === 'WITHDRAW'
          ? '撤回公开作品？'
          : action === 'DISABLE_COPY'
            ? '关闭复制权限？'
            : '取消当前待审版本？',
      content:
        action === 'WITHDRAW'
          ? '作品视频、封面和完整工程将一起停止公开；已完成的独立副本保留。'
          : '该操作保留你的工作草稿，已完成的独立副本不受影响。',
      onOk: () => change(action),
    })
  async function loadVersions() {
    if (versionsLock.current || !current()) return
    versionsLock.current = true
    setVersions(null)
    setHistoryError('')
    setVersionsLoading(true)
    try {
      const items = await canvasPublicationVersions(project.projectId)
      if (current()) setVersions(items)
    } catch (error) {
      if (current()) setHistoryError(apiErrorMessage(error, '发布记录读取失败'))
    } finally {
      versionsLock.current = false
      if (current()) setVersionsLoading(false)
    }
  }
  const blocked = !pending && ((publicCanvas && !!state?.pendingId) || !state)
  const disabled = loading || submitting || !!pending
  const mediaField = (
    type: 'VIDEO' | 'IMAGE',
    selection: CanvasPublicationMediaSelection | null,
  ) => {
    const label = type === 'VIDEO' ? '视频' : '封面'
    return (
      <div className="studio-publication__field">
        <span className="studio-publication__label">
          作品{label}
          {publicCanvas && <span aria-hidden> *</span>}
        </span>
        {selection ? (
          <div className="studio-publication__preview">
            {type === 'VIDEO' ? (
              <video
                src={canvasResourceDisplayUrl(selection.resource)}
                controls
                preload="metadata"
                playsInline
                aria-label="作品视频预览"
              />
            ) : (
              <img
                src={canvasResourceDisplayUrl(selection.resource)}
                alt="作品封面"
              />
            )}
            <div className="studio-publication__media-actions">
              <StudioCanvasTooltip title={`更换${label}`}>
                <Button
                  type="text"
                  className="studio-publication__media-action"
                  aria-label={`更换${label}`}
                  icon={<EditOutlined />}
                  disabled={disabled}
                  onClick={() => setPicker(type)}
                />
              </StudioCanvasTooltip>
              <StudioCanvasTooltip title={`移除${label}`}>
                <Button
                  type="text"
                  className="studio-publication__media-action"
                  aria-label={`移除${label}`}
                  icon={<DeleteOutlined />}
                  disabled={disabled}
                  onClick={() =>
                    type === 'VIDEO' ? setVideo(null) : setCover(null)
                  }
                />
              </StudioCanvasTooltip>
            </div>
          </div>
        ) : (
          <button
            type="button"
            className="studio-publication__add"
            disabled={disabled}
            onClick={() => setPicker(type)}
          >
            <PlusOutlined aria-hidden />
            <span>上传作品{label}</span>
          </button>
        )}
      </div>
    )
  }

  return (
    <>
      {confirmationContext}
      {feedbackContext}
      <Modal
        open
        title={historyOpen ? '发布记录' : <div className="studio-publication__title"><span>发布至案例广场</span><Button type="text" size="small" disabled={submitting} onClick={() => { setHistoryOpen(true); void loadVersions() }}>发布记录</Button></div>}
        width={680}
        centered
        className="studio-publication-modal"
        wrapClassName="studio-publication-modal-wrap"
        mask={{ closable: !submitting && !picker }}
        closable={!submitting && !picker}
        keyboard={!submitting && !picker}
        onCancel={() => onOpenChange(false)}
        footer={<>
          {!historyOpen && <PublicationFormFeedback error={error} pending={!!pending && !submitting} />}
          <div className="studio-publication__footer">
            {historyOpen ? (
              <Button onClick={() => { setHistoryOpen(false); void load() }}>返回发布</Button>
            ) : (
              <>
                <Button
                  disabled={submitting || !!picker}
                  onClick={() => onOpenChange(false)}
                >
                  取消
                </Button>
                <StudioCanvasTooltip
                  title={
                    blocked
                      ? state?.pendingId
                        ? '已有作品正在审核中'
                        : '请先重新加载发布信息'
                      : undefined
                  }
                >
                  <Button
                    type="primary"
                    aria-label={
                      pending
                        ? '重试核实提交'
                        : publicCanvas
                          ? '提交审核'
                          : '保存发布草稿'
                    }
                    aria-busy={submitting}
                    loading={submitting}
                    disabled={loading || blocked || !!picker}
                    onClick={() => void submit()}
                  >
                    {pending
                      ? '重试核实提交'
                      : publicCanvas
                        ? '提交审核'
                        : '保存发布草稿'}
                  </Button>
                </StudioCanvasTooltip>
              </>
            )}
          </div>
        </>}
      >
        {historyOpen ? <StudioPublicationHistory versions={versions} loading={versionsLoading} error={historyError} onRetry={() => void loadVersions()} /> : null}
        <div className="studio-publication" hidden={historyOpen} aria-busy={loading || submitting}>
          <p className="studio-publication__hint">
            选择视频和封面，填写介绍。公开后将展示作品、完整画布及引用文件的原始内容和内嵌元数据，请确认你有权公开这些内容。
          </p>
          {loading ? (
            <p role="status">正在加载发布信息…</p>
          ) : (
            <>
              {state && (
                <div className="studio-publication__notice">
                  <PublicationSummary state={state} />
                  {state.isPublic || state.pendingId ? <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 8 }}>
                    {state.isPublic && (
                      <Button
                        disabled={disabled}
                        onClick={() => confirmChange('WITHDRAW')}
                      >
                        撤回公开
                      </Button>
                    )}
                    {state.isPublic && state.allowCopy && (
                      <Button
                        disabled={disabled}
                        onClick={() => confirmChange('DISABLE_COPY')}
                      >
                        关闭复制
                      </Button>
                    )}
                    {state.pendingId && (
                      <Button
                        disabled={disabled}
                        onClick={() => confirmChange('CANCEL_REVIEW')}
                      >
                        取消待审
                      </Button>
                    )}
                  </div> : null}
                </div>
              )}
              <div className="studio-publication__field">
                <label
                  htmlFor="studio-publication-name"
                  className="studio-publication__label"
                >
                  发布名称
                </label>
                <Input
                  id="studio-publication-name"
                  value={projectName}
                  maxLength={100}
                  disabled={disabled}
                  onChange={(e) => setProjectName(e.target.value)}
                  placeholder={project.projectName}
                />
              </div>
              {state?.pendingId && !pending && (
                <p role="status" className="studio-publication__notice">
                  作品正在审核中，通过后将公开展示。
                </p>
              )}
              <div className="studio-publication__media-grid">
                {mediaField('VIDEO', video)}
                {mediaField('IMAGE', cover)}
              </div>
              <div className="studio-publication__field">
                <label
                  className="studio-publication__label"
                  htmlFor="studio-publication-desc"
                >
                  作品描述{publicCanvas && <span aria-hidden> *</span>}
                </label>
                <Input.TextArea
                  id="studio-publication-desc"
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  classNames={{ root: 'studio-publication__description' }}
                  styles={{
                    root: {
                      background: 'var(--studio-ui-field)',
                      color: 'var(--studio-ui-text)',
                    },
                    textarea: {
                      color: 'var(--studio-ui-text)',
                      background: 'transparent',
                    },
                    count: { color: 'var(--studio-ui-muted)' },
                  }}
                  disabled={disabled}
                  maxLength={500}
                  rows={4}
                  showCount
                  placeholder="介绍一下你的作品（最多500字）"
                />
              </div>
              <div className="studio-publication__permission">
                <div>
                  <strong>公开作品与画布</strong>
                  <p className="studio-publication__hint">
                    关闭时仅保存草稿，旧公开版本保持不变；停止公开请使用“撤回公开”。
                  </p>
                </div>
                <Switch
                  checked={publicCanvas}
                  disabled={disabled}
                  onChange={setPublicCanvas}
                  aria-label="公开作品与画布"
                />
              </div>
              <div className="studio-publication__permission">
                <div>
                  <strong>允许复制画布</strong>
                  <p className="studio-publication__hint">
                    审核通过后，其他创作者可复制此版本继续创作。
                  </p>
                </div>
                <Switch
                  checked={allowCopy}
                  disabled={disabled}
                  onChange={setAllowCopy}
                  aria-label="允许复制画布"
                />
              </div>
            </>
          )}
          {!loading && !state && (
            <Button
              onClick={() => {
                setLoading(true)
                setError('')
                void load()
              }}
            >
              重新加载
            </Button>
          )}
        </div>
      </Modal>
      {picker && (
        <StudioCanvasPublicationMediaPicker
          key={picker}
          type={picker}
          runtime={runtime}
          video={video?.resource}
          onClose={() => setPicker(null)}
          onSelect={(selection) => {
            if (picker === 'VIDEO') setVideo(selection)
            else setCover(selection)
            setError('')
            setPicker(null)
          }}
        />
      )}
    </>
  )
}
