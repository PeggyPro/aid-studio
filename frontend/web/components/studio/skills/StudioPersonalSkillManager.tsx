'use client'

/* eslint-disable @next/next/no-img-element -- Skill covers are authorized runtime uploads. */
import { useEffect, useRef, useState } from 'react'
import { Modal } from 'antd'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { canvasUserModelList } from '@/utils/business/model'
import { apiErrorMessage } from '@/utils/apiError'
import * as api from '@/utils/business/canvasPersonalSkill'
import { skillDraftPayload } from '@/utils/studio/canvasSkillAuthoring'
import type {
  CanvasPersonalSkill,
  CanvasSkillDraft,
  CanvasSkillValidation,
  CanvasSkillVersion
} from '@/types/canvas-skill-api'
import type { UserModelListItem } from '@/types/business-api'
import { StudioSkillDraftEditor } from './StudioSkillDraftEditor'
import './studio-personal-skill.css'

export function StudioPersonalSkillManager({
  open,
  onClose,
  onChanged
}: {
  open: boolean
  onClose: () => void
  onChanged: () => void
}) {
  const closeRequestRef = useRef<(() => void) | null>(null)
  return (
    <Modal
      open={open}
      onCancel={() => closeRequestRef.current?.()}
      title={null}
      footer={null}
      closable={false}
      width={1120}
      centered
      destroyOnHidden
      className="studio-personal-skill-modal"
      getContainer={() => document.querySelector<HTMLElement>('.studio-workspace') || document.body}
    >
      {open ? (
        <PersonalSkillWorkspace
          onClose={onClose}
          onChanged={onChanged}
          closeRequestRef={closeRequestRef}
        />
      ) : null}
    </Modal>
  )
}

function PersonalSkillWorkspace({
  onClose,
  onChanged,
  closeRequestRef
}: Omit<Parameters<typeof StudioPersonalSkillManager>[0], 'open'> & {
  closeRequestRef: { current: (() => void) | null }
}) {
  const [modal, modalContext] = Modal.useModal()
  const [items, setItems] = useState<CanvasPersonalSkill[]>([])
  const [page, setPage] = useState(1),
    [total, setTotal] = useState(0)
  const [query, setQuery] = useState(''),
    [keyword, setKeyword] = useState('')
  const [selected, setSelected] = useState<CanvasPersonalSkill | null>(null)
  const [draft, setDraft] = useState<CanvasSkillDraft | null>(null)
  const [baseline, setBaseline] = useState('')
  const [versions, setVersions] = useState<CanvasSkillVersion[]>([])
  const [versionPage, setVersionPage] = useState(1),
    [versionTotal, setVersionTotal] = useState(0)
  const [versionCode, setVersionCode] = useState('')
  const [publishedDraft, setPublishedDraft] = useState(false)
  const [validation, setValidation] = useState<CanvasSkillValidation | null>(null)
  const [models, setModels] = useState<UserModelListItem[]>([])
  const [allowPublic, setAllowPublic] = useState(false)
  const [policyError, setPolicyError] = useState('')
  const [busy, setBusy] = useState(false),
    [listLoading, setListLoading] = useState(false)
  const [error, setError] = useState(''),
    [notice, setNotice] = useState('')
  const [tab, setTab] = useState<'files' | 'versions' | 'identity'>('files')
  const [identity, setIdentity] = useState({
    name: '',
    description: '',
    visibility: 'PRIVATE' as 'PRIVATE' | 'PUBLIC',
    status: '0' as '0' | '1'
  })
  const [disk, setDisk] = useState('KEEP')
  const [diskOpen, setDiskOpen] = useState(false)
  const lock = useRef(false),
    alive = useRef(true)
  const leaveDialog = useRef<Promise<boolean> | null>(null)
  const leaveResolver = useRef<((value: boolean) => void) | null>(null)
  const dirty = Boolean(draft && JSON.stringify(skillDraftPayload(draft)) !== baseline)
  const identityDirty = Boolean(
    selected &&
      (identity.name !== selected.name ||
        identity.description !== (selected.description || '') ||
        identity.visibility !== selected.visibility ||
        identity.status !== selected.status ||
        disk !== 'KEEP')
  )
  const confirmLeave = () => {
    if (!dirty && !identityDirty) return Promise.resolve(true)
    if (leaveDialog.current) return leaveDialog.current
    leaveDialog.current = new Promise<boolean>((resolve) => {
      leaveResolver.current = resolve
      modal.confirm({
        title: '保留未保存的修改？',
        content: '当前 Skill 有未保存修改。继续会放弃这些修改。',
        okText: '放弃修改',
        cancelText: '继续编辑',
        centered: true,
        className: 'create-flow-modal',
        getContainer: () => document.querySelector<HTMLElement>('.studio-workspace') || document.body,
        onOk: () => resolve(true),
        onCancel: () => resolve(false)
      })
    }).finally(() => {
      leaveDialog.current = null
      leaveResolver.current = null
    })
    return leaveDialog.current
  }
  const close = async () => {
    if (!lock.current && (await confirmLeave()) && !lock.current && alive.current) onClose()
  }
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      leaveResolver.current?.(false)
    }
  }, [])
  useEffect(() => {
    closeRequestRef.current = () => {
      void close()
    }
    return () => {
      closeRequestRef.current = null
    }
  })

  useEffect(() => {
    let active = true
    void api
      .personalSkillPublicationSettings()
      .then((result) => {
        if (active) setAllowPublic(result.allowUserPublicSkills)
      })
      .catch((cause) => {
        if (active) setPolicyError(apiErrorMessage(cause, '公开配置读取失败，暂不能公开'))
      })
    void canvasUserModelList({ modelType: 'text' })
      .then((result) => {
        if (active) setModels(result)
      })
      .catch((cause) => {
        if (active) setNotice('模型目录读取失败，原配置保留：' + apiErrorMessage(cause))
      })
    return () => {
      active = false
    }
  }, [])
  useEffect(() => {
    let active = true
    setListLoading(true)
    void api
      .personalSkillList(page, keyword)
      .then((result) => {
        if (active) {
          setItems(result.items)
          setTotal(result.total)
        }
      })
      .catch((cause) => {
        if (active) setError(apiErrorMessage(cause, '我的 Skill 读取失败'))
      })
      .finally(() => {
        if (active) setListLoading(false)
      })
    return () => {
      active = false
    }
  }, [page, keyword])

  const refresh = async (resetIdentity = false) => {
    const result = await api.personalSkillList(page, keyword)
    if (!alive.current) return
    setItems(result.items)
    setTotal(result.total)
    // Current identity may live on a different page; do not infer deletion from pagination.
    const fresh = result.items.find((item) => item.id === selected?.id)
    if (fresh) {
      setSelected(fresh)
      if (resetIdentity) {
        setIdentity({
          name: fresh.name,
          description: fresh.description || '',
          visibility: fresh.visibility,
          status: fresh.status
        })
        setDisk('KEEP')
      }
    }
  }
  const choose = async (item: CanvasPersonalSkill, baseVersionId?: string) => {
    if (lock.current || !(await confirmLeave()) || lock.current || !alive.current) return
    lock.current = true
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const [body, pageResult] = await Promise.all([
        api.personalSkillDraft(item.id, baseVersionId),
        api.personalSkillVersions(item.id)
      ])
      if (!alive.current) return
      const next = {
        ...body,
        resources: body.resources || [],
        relations: body.relations || []
      }
      setSelected(item)
      setIdentity({
        name: item.name,
        description: item.description || '',
        visibility: item.visibility,
        status: item.status
      })
      setDisk('KEEP')
      setDraft(next)
      setBaseline(JSON.stringify(skillDraftPayload(next)))
      setValidation(null)
      setVersions(pageResult.items)
      setVersionPage(1)
      setVersionTotal(pageResult.total)
      setVersionCode('')
      setPublishedDraft(false)
      setTab('files')
      if (baseVersionId && body.baseVersionId !== baseVersionId)
        setNotice('已载入服务端现有编辑草稿；该草稿优先于所选基础版本，未覆盖已有草稿。')
    } catch (cause) {
      if (alive.current) setError(apiErrorMessage(cause, '草稿读取失败'))
    } finally {
      lock.current = false
      if (alive.current) setBusy(false)
    }
  }
  const run = async (work: () => Promise<void>) => {
    if (lock.current) return
    lock.current = true
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await work()
    } catch (cause) {
      if (alive.current) setError(apiErrorMessage(cause, '操作失败；编辑内容已保留'))
    } finally {
      lock.current = false
      if (alive.current) setBusy(false)
    }
  }
  const committed = async (text: string, resetIdentity = false) => {
    if (!alive.current) return
    setNotice(text)
    onChanged()
    try {
      await refresh(resetIdentity)
    } catch (cause) {
      if (alive.current) setError('操作已成功，列表刷新失败，请重新读取：' + apiErrorMessage(cause))
    }
  }
  const save = () =>
    run(async () => {
      if (!draft) return
      const result = await api.personalSkillSave(skillDraftPayload(draft))
      if (!alive.current) return
      const next = {
        ...result,
        resources: result.resources || [],
        relations: result.relations || []
      }
      setDraft(next)
      setBaseline(JSON.stringify(skillDraftPayload(next)))
      setValidation(null)
      await committed('草稿已保存，当前正式版本和在途运行保持原版本。')
    })
  const validate = () =>
    run(async () => {
      if (draft) {
        const result = await api.personalSkillValidate(skillDraftPayload(draft, false))
        if (alive.current) setValidation(result)
      }
    })
  const publish = () =>
    run(async () => {
      if (
        !selected ||
        !draft?.draftId ||
        !draft.draftDigest ||
        dirty ||
        publishedDraft ||
        !versionCode.trim()
      )
        return
      const version = await api.personalSkillPublish(
        draft.draftId,
        draft.draftDigest,
        versionCode.trim()
      )
      if (!alive.current) return
      setPublishedDraft(true)
      setVersionCode('')
      setTab('versions')
      await committed('正式版本已发布，请在版本列表中启用；发布不会自动公开。')
      try {
        const [result, body] = await Promise.all([
          api.personalSkillVersions(selected.id),
          api.personalSkillDraft(selected.id, version.id)
        ])
        if (alive.current) {
          const next = { ...body, resources: body.resources || [], relations: body.relations || [] }
          setDraft(next)
          setBaseline(JSON.stringify(skillDraftPayload(next)))
          setValidation(null)
          setPublishedDraft(false)
          setVersions(result.items)
          setVersionPage(1)
          setVersionTotal(result.total)
        }
      } catch (cause) {
        if (alive.current)
          setError(
            '版本已发布，编辑草稿读取失败；请重新选择 Skill 后继续：' + apiErrorMessage(cause)
          )
      }
    })
  const activate = (versionId: string) =>
    run(async () => {
      if (!selected) return
      await api.personalSkillActivate(selected.id, versionId, selected.currentVersionId || null)
      if (alive.current)
        setSelected((current) => (current ? { ...current, currentVersionId: versionId } : current))
      await committed('启用版本已切换，新运行使用该版本；已受理运行继续使用固定版本。')
    })
  const saveIdentity = () =>
    run(async () => {
      if (!selected) return
      await api.personalSkillIdentity({
        id: selected.id,
        ...identity,
        ...(identity.visibility === 'PRIVATE'
          ? { cacheToDisk: false }
          : disk !== 'KEEP'
            ? { cacheToDisk: disk === 'ON' }
            : {})
      })
      if (alive.current) {
        setSelected((current) => (current ? { ...current, ...identity } : current))
        setDisk('KEEP')
      }
      await committed('Skill 设置已保存。', true)
    })
  const cover = (file: File | undefined) =>
    run(async () => {
      if (!file || !selected) return
      if (
        !/^image\/(png|jpeg|gif|bmp|webp)$/.test(file.type) ||
        !file.size ||
        file.size > 20 * 1024 * 1024
      )
        throw new Error('请选择 20 MiB 以内的 JPG、PNG、GIF、BMP 或 WebP 图片')
      const result = await api.personalSkillCover(selected.id, file)
      if (alive.current)
        setSelected((current) => (current ? { ...current, iconUrl: result.url } : current))
      // Upload binds the cover on the server; don't discard unrelated identity or package edits.
      if (alive.current) {
        setNotice('封面已上传并绑定，公开时仍会校验完整包和权限。')
        onChanged()
      }
    })
  const publishReason = busy
    ? '正在处理，请稍候'
    : publishedDraft
      ? '此草稿已发布，请重新读取编辑草稿'
      : !draft?.draftId
        ? '请先保存草稿'
        : dirty
          ? '请保存修改后再发布'
          : !versionCode.trim()
            ? '请填写新版本号，例如 1.0.1'
            : undefined
  return (
    <section className="studio-personal-skill" aria-label="我的 Skill" aria-busy={busy}>
      {modalContext}
      <header className="studio-personal-skill__header">
        <div>
          <StudioIconGlyph name="skill" />
          <span>
            <strong>我的 Skill</strong>
            <small>将创作经验整理成可复用的方法</small>
          </span>
        </div>
        <StudioCanvasTooltip title="关闭我的 Skill">
          <button
            type="button"
            aria-label="关闭我的 Skill"
            disabled={busy}
            onClick={() => void close()}
          >
            <StudioIconGlyph name="close" />
          </button>
        </StudioCanvasTooltip>
      </header>
      <div className="studio-personal-skill__layout">
        <aside className="studio-personal-skill__rail">
          <form
            onSubmit={(event) => {
              event.preventDefault()
              if (!busy) {
                setKeyword(query.trim())
                setPage(1)
              }
            }}
          >
            <input
              aria-label="搜索我的 Skill"
              disabled={busy}
              value={query}
              maxLength={100}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索名称或编码"
            />
            <button type="submit" disabled={busy} aria-label="搜索">
              搜索
            </button>
          </form>
          <div className="studio-personal-skill__list" aria-busy={listLoading}>
            {items.map((item) => (
              <button
                type="button"
                key={item.id}
                disabled={busy}
                aria-current={selected?.id === item.id ? 'page' : undefined}
                onClick={() => void choose(item)}
              >
                <StudioIconGlyph name="skill" />
                <span>
                  <strong>{item.name}</strong>
                  <small>
                    {item.visibility === 'PUBLIC' ? '公开' : '仅自己可见'} ·{' '}
                    {item.status === '1'
                      ? '已停用'
                      : item.currentVersionId
                        ? '有启用版本'
                        : '待发布启用'}
                  </small>
                </span>
              </button>
            ))}
            {!items.length && !listLoading ? <p>尚无 Skill，可从智能体对话中创建。</p> : null}
          </div>
          <div className="studio-skill-pagination">
            <button
              type="button"
              disabled={page <= 1 || busy}
              onClick={() => setPage((value) => value - 1)}
            >
              上一页
            </button>
            <span>
              {page} / {Math.max(1, Math.ceil(total / 50))}
            </span>
            <button
              type="button"
              disabled={page * 50 >= total || busy}
              onClick={() => setPage((value) => value + 1)}
            >
              下一页
            </button>
          </div>
        </aside>
        <main className="studio-personal-skill__main">
          <div className="studio-skill-feedback" aria-live="polite">
            {error ? (
              <p role="alert">
                {error}
                <button type="button" disabled={busy} onClick={() => void run(refresh)}>
                  重新读取列表
                </button>
              </p>
            ) : null}
            {notice ? <p>{notice}</p> : null}
          </div>
          {selected && draft ? (
            <>
              <div className="studio-skill-heading">
                <div>
                  <h2>{selected.name}</h2>
                  <small>
                    {selected.skillCode} · {dirty ? '有未保存修改' : '草稿已同步'}
                  </small>
                </div>
                <div className="studio-skill-tabs" role="tablist" aria-label="维护内容">
                  {(
                    [
                      ['files', '文件'],
                      ['versions', '版本'],
                      ['identity', '展示与公开']
                    ] as const
                  ).map(([key, label]) => (
                    <button
                      type="button"
                      key={key}
                      role="tab"
                      aria-selected={tab === key}
                      onClick={() => setTab(key)}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
              {tab === 'files' ? (
                <StudioSkillDraftEditor
                  key={selected.id + ':' + (draft.baseVersionId || '')}
                  draft={draft}
                  disabled={busy || publishedDraft}
                  models={models}
                  onChange={(value) => {
                    setDraft(value)
                    setValidation(null)
                  }}
                />
              ) : tab === 'versions' ? (
                <div className="studio-skill-versions">
                  <p>发布保存正式版本，启用决定下一次运行。当前任务不会被版本切换替换。</p>
                  {versions.map((version) => (
                    <article key={version.id}>
                      <span>
                        <strong>{version.versionCode}</strong>
                        <small>
                          {['PRIVATE', 'PUBLISHED'].includes(version.publishStatus || '')
                            ? '正式版本'
                            : version.publishStatus}{' '}
                          · {version.packageDigest?.slice(0, 16)}
                        </small>
                      </span>
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void choose(selected, version.id)}
                      >
                        从此版本编辑
                      </button>
                      <button
                        type="button"
                        disabled={
                          busy || selected.currentVersionId === version.id || version.status === '1'
                        }
                        onClick={() => void activate(version.id)}
                      >
                        {selected.currentVersionId === version.id ? '当前启用' : '启用此版本'}
                      </button>
                    </article>
                  ))}
                  <div className="studio-skill-pagination">
                    <button
                      type="button"
                      disabled={busy || versionPage <= 1}
                      onClick={() =>
                        void run(async () => {
                          const result = await api.personalSkillVersions(
                            selected.id,
                            versionPage - 1
                          )
                          if (alive.current) {
                            setVersions(result.items)
                            setVersionPage((value) => value - 1)
                            setVersionTotal(result.total)
                          }
                        })
                      }
                    >
                      上一页
                    </button>
                    <span>{versionPage}</span>
                    <button
                      type="button"
                      disabled={busy || versionPage * 20 >= versionTotal}
                      onClick={() =>
                        void run(async () => {
                          const result = await api.personalSkillVersions(
                            selected.id,
                            versionPage + 1
                          )
                          if (alive.current) {
                            setVersions(result.items)
                            setVersionPage((value) => value + 1)
                            setVersionTotal(result.total)
                          }
                        })
                      }
                    >
                      下一页
                    </button>
                  </div>
                </div>
              ) : (
                <div className="studio-skill-identity">
                  <div className="studio-skill-cover">
                    {selected.iconUrl ? (
                      <img src={selected.iconUrl} alt={`${selected.name}封面`} />
                    ) : (
                      <StudioIconGlyph name="skill" />
                    )}
                    <label>
                      上传封面
                      <input
                        type="file"
                        aria-label="上传 Skill 封面"
                        accept="image/png,image/jpeg,image/gif,image/bmp,image/webp"
                        disabled={busy}
                        onChange={(event) => {
                          const file = event.target.files?.[0]
                          event.target.value = ''
                          void cover(file)
                        }}
                      />
                    </label>
                    <small>真实图片，最大 20 MiB；公开前必须上传封面。</small>
                  </div>
                  <label>
                    中文名称
                    <input
                      disabled={busy}
                      value={identity.name}
                      maxLength={100}
                      onChange={(event) =>
                        setIdentity((current) => ({
                          ...current,
                          name: event.target.value
                        }))
                      }
                    />
                  </label>
                  <label>
                    介绍
                    <textarea
                      disabled={busy}
                      value={identity.description}
                      maxLength={1000}
                      onChange={(event) =>
                        setIdentity((current) => ({
                          ...current,
                          description: event.target.value
                        }))
                      }
                    />
                  </label>
                  <label className="studio-skill-switch">
                    <span>
                      <strong>公开 Skill</strong>
                      <small>
                        {policyError ||
                          (allowPublic
                            ? '公开正式内容供其他用户调用，依赖和资源仍须通过权限校验。'
                            : '平台目前未开放新的用户公开；已有公开项可以转为私有。')}
                      </small>
                    </span>
                    <StudioCanvasTooltip
                      title={
                        !allowPublic && identity.visibility !== 'PUBLIC'
                          ? '平台暂未开放用户公开'
                          : '仅变更公开状态，不自动发布或启用版本'
                      }
                    >
                      <input
                        type="checkbox"
                        aria-label="公开 Skill"
                        disabled={busy || (!allowPublic && identity.visibility !== 'PUBLIC')}
                        checked={identity.visibility === 'PUBLIC'}
                        onChange={(event) => {
                          setIdentity((current) => ({
                            ...current,
                            visibility: event.target.checked ? 'PUBLIC' : 'PRIVATE'
                          }))
                          setDisk('KEEP')
                        }}
                      />
                    </StudioCanvasTooltip>
                  </label>
                  <label className="studio-skill-switch">
                    <span>
                      <strong>启用 Skill</strong>
                      <small>停用前服务端检查有效依赖；有启用版本才可执行。</small>
                    </span>
                    <input
                      type="checkbox"
                      aria-label="启用 Skill"
                      disabled={busy}
                      checked={identity.status === '0'}
                      onChange={(event) =>
                        setIdentity((current) => ({
                          ...current,
                          status: event.target.checked ? '0' : '1'
                        }))
                      }
                    />
                  </label>
                  {identity.visibility === 'PUBLIC' ? (
                    <label>
                      公开包落盘设置
                      <StudioCanvasDropdownPopover
                        ariaLabel="公开包落盘设置"
                        open={diskOpen && !busy}
                        onOpenChange={setDiskOpen}
                        items={[
                          { key: 'KEEP', label: '保持服务端当前设置' },
                          { key: 'ON', label: '允许缓存落盘' },
                          { key: 'OFF', label: '关闭缓存落盘' }
                        ].map((item) => ({ ...item, disabled: busy }))}
                        activeKey={disk}
                        onSelect={setDisk}
                        trigger={
                          <button type="button" aria-label="公开包落盘设置" disabled={busy}>
                            {disk === 'KEEP'
                              ? '保持服务端当前设置'
                              : disk === 'ON'
                                ? '允许缓存落盘'
                                : '关闭缓存落盘'}
                          </button>
                        }
                      />
                    </label>
                  ) : null}
                  <button
                    type="button"
                    className="studio-skill-primary"
                    disabled={busy || !identityDirty || !identity.name.trim()}
                    onClick={() => void saveIdentity()}
                  >
                    保存展示与公开设置
                  </button>
                </div>
              )}
              {validation ? (
                <div className="studio-skill-validation" role="status">
                  <strong>{validation.valid ? '包校验通过' : '请先修正包内容'}</strong>
                  {[...(validation.errors || []), ...(validation.warnings || [])].map(
                    (issue, i) => (
                      <p key={i}>
                        {issue.field}：{issue.message}
                      </p>
                    )
                  )}
                </div>
              ) : null}
              {tab === 'files' ? (
                <footer className="studio-skill-actions">
                  <span className="studio-skill-actions__hint">
                    {busy
                      ? '正在处理…'
                      : publishedDraft
                        ? '草稿已发布，请重新选择 Skill 后继续编辑'
                        : '保存草稿不会替换正式版本'}
                  </span>
                  <button type="button" disabled={busy} onClick={() => void validate()}>
                    校验
                  </button>
                  <button
                    type="button"
                    className="studio-skill-primary"
                    disabled={busy || publishedDraft || !dirty}
                    onClick={() => void save()}
                  >
                    保存草稿
                  </button>
                  <label>
                    版本号
                    <input
                      aria-label="发布版本号"
                      disabled={busy}
                      placeholder="1.0.1"
                      value={versionCode}
                      onChange={(event) => setVersionCode(event.target.value)}
                    />
                  </label>
                  <StudioCanvasTooltip title={publishReason || '发布新的正式版本，随后可手动启用'}>
                    <button
                      type="button"
                      disabled={busy || Boolean(publishReason)}
                      onClick={() => void publish()}
                    >
                      发布版本
                    </button>
                  </StudioCanvasTooltip>
                </footer>
              ) : null}
            </>
          ) : (
            <div className="studio-skill-welcome">
              <StudioIconGlyph name="skill" />
              <h2>维护你的创作方法</h2>
              <p>选择左侧的 Skill，编辑文件、管理版本与公开设置。</p>
              <small>新建与导入请从智能体对话面板的 Skill「创建」入口开始。</small>
            </div>
          )}
        </main>
      </div>
    </section>
  )
}
