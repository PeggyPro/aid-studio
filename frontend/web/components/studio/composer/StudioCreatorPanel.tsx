'use client'

import { useEffect, useState } from 'react'
import { message } from 'antd'
import { canvasCreatorList, canvasCreatorPost, canvasCreatorVersionList, type CanvasCreatorKind } from '@/utils/business/canvasRuntime'
import { apiErrorMessage } from '@/utils/apiError'

type OwnDefinition = { id: string; skillCode?: string; agentCode?: string; name: string; status: '0' | '1'; currentVersionId?: string | null }
type OwnVersion = { id: string; versionCode: string; publishStatus?: string; isCurrent?: boolean }
type Draft = Record<string, unknown> & { draftId?: string; draftDigest?: string }
type Validation = { valid: boolean; draftDigest?: string; errors?: Array<{ field: string; message: string }>; warnings?: Array<{ field: string; message: string }> }

const SKILL_FIELDS = ['skillId', 'baseVersionId', 'modelCode', 'defaultModelCode', 'selectableModelCodes', 'systemPrompt', 'inputSchemaJson', 'outputSchemaJson', 'definitionJson', 'maxOutputTokens', 'contextWindowTokens', 'safetyMarginTokens', 'resources', 'relations']
const AGENT_FIELDS = ['agentId', 'baseVersionId', 'instruction', 'executorType', 'delegatedSkillCode', 'outputKind', 'defaultModelCode', 'selectableModelCodes', 'allowedSkillCodes', 'allowedToolCodes', 'maxSteps', 'maxTaskCount', 'maxDurationSeconds', 'maxEstimatedTokens', 'clarificationEnabled', 'steps']

function editablePayload(kind: CanvasCreatorKind, draft: Draft): Record<string, unknown> {
  const fields = kind === 'skill' ? SKILL_FIELDS : AGENT_FIELDS
  return Object.fromEntries(fields.filter((field) => draft[field] !== undefined).map((field) => [field, draft[field]]))
}

export function StudioCreatorPanel({ skillOnly = false }: { skillOnly?: boolean } = {}) {
  const [kind, setKind] = useState<CanvasCreatorKind>('skill')
  const [loading, setLoading] = useState(false)
  const [items, setItems] = useState<OwnDefinition[]>([])
  const [selected, setSelected] = useState<OwnDefinition | null>(null)
  const [versions, setVersions] = useState<OwnVersion[]>([])
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [draftText, setDraftText] = useState('')
  const [versionCode, setVersionCode] = useState('1.0.0')
  const [validation, setValidation] = useState<Validation | null>(null)
  const [dirty, setDirty] = useState(false)

  const loadList = async (targetKind = kind) => {
    setLoading(true)
    try {
      const result = await canvasCreatorList<OwnDefinition>(targetKind, { pageSize: 100 })
      setItems(result.items)
    } catch (error) { message.error(apiErrorMessage(error, '我的能力加载失败')) }
    finally { setLoading(false) }
  }

  useEffect(() => {
    let active = true
    void canvasCreatorList<OwnDefinition>(kind, { pageSize: 100 })
      .then((result) => { if (active) setItems(result.items) })
      .catch((error) => message.error(apiErrorMessage(error, '我的能力加载失败')))
    return () => { active = false }
  }, [kind])

  const selectDefinition = async (item: OwnDefinition) => {
    setSelected(item)
    setLoading(true)
    setValidation(null)
    try {
      const idKey = kind === 'skill' ? 'skillId' : 'agentId'
      const [detail, list] = await Promise.all([
        canvasCreatorPost<Draft>(kind, 'draft/detail', { [idKey]: item.id }),
        canvasCreatorVersionList<OwnVersion>(kind, item.id)
      ])
      setDraft(detail)
      setDraftText(JSON.stringify(editablePayload(kind, detail), null, 2))
      setVersions(list.items)
      setDirty(false)
    } catch (error) { message.error(apiErrorMessage(error, '草稿加载失败')) }
    finally { setLoading(false) }
  }

  const readPayload = () => {
    if (!selected) throw new Error('请先选择能力')
    const parsed = JSON.parse(draftText) as Draft
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error('草稿定义必须是 JSON 对象')
    const idKey = kind === 'skill' ? 'skillId' : 'agentId'
    return { ...editablePayload(kind, parsed), [idKey]: selected.id }
  }

  const create = async () => {
    if (!code.trim() || !name.trim()) return message.warning('请填写编码和名称')
    setLoading(true)
    try {
      const created = await canvasCreatorPost<OwnDefinition>(kind, 'create', {
        [kind === 'skill' ? 'skillCode' : 'agentCode']: code.trim(), name: name.trim(),
        description: description.trim(), visibility: 'PRIVATE'
      })
      setCode(''); setName(''); setDescription('')
      await loadList()
      await selectDefinition(created)
      message.success('已创建私人能力')
    } catch (error) { message.error(apiErrorMessage(error, '创建失败')) }
    finally { setLoading(false) }
  }

  const validate = async () => {
    try {
      const result = await canvasCreatorPost<Validation>(kind, 'draft/validate', readPayload())
      setValidation(result)
      message[result.valid ? 'success' : 'warning'](result.valid ? '草稿校验通过' : '草稿存在需要修正的字段')
    } catch (error) { message.error(apiErrorMessage(error, '草稿校验失败')) }
  }

  const save = async () => {
    try {
      const result = await canvasCreatorPost<Draft>(kind, 'draft/save', {
        ...readPayload(), ...(draft?.draftId ? { draftId: draft.draftId, draftDigest: draft.draftDigest } : {})
      })
      setDraft(result)
      setDraftText(JSON.stringify(editablePayload(kind, result), null, 2))
      setDirty(false)
      setValidation(null)
      message.success('草稿已保存')
    } catch (error) { message.error(apiErrorMessage(error, '草稿保存失败')) }
  }

  const publish = async () => {
    if (!draft?.draftId || !draft.draftDigest || dirty) return message.warning('请先保存当前草稿')
    try {
      await canvasCreatorPost(kind, 'draft/publish', { draftId: draft.draftId, draftDigest: draft.draftDigest, versionCode })
      message.success('版本已发布到私人能力')
      if (selected) await selectDefinition(selected)
      await loadList()
    } catch (error) { message.error(apiErrorMessage(error, '发布失败')) }
  }

  const activate = async (versionId: string) => {
    if (!selected) return
    try {
      await canvasCreatorPost(kind, 'version/activate', {
        [kind === 'skill' ? 'skillId' : 'agentId']: selected.id,
        versionId,
        ...(selected.currentVersionId ? { expectedCurrentVersionId: selected.currentVersionId } : {})
      })
      message.success('当前版本已切换')
      await loadList()
      await selectDefinition({ ...selected, currentVersionId: versionId })
    } catch (error) { message.error(apiErrorMessage(error, '版本切换失败')) }
  }

  const toggleStatus = async () => {
    if (!selected) return
    const status = selected.status === '0' ? '1' : '0'
    try {
      await canvasCreatorPost(kind, 'status', { id: selected.id, status })
      setSelected({ ...selected, status })
      await loadList()
      message.success(status === '0' ? '已启用' : '已停用')
    } catch (error) { message.error(apiErrorMessage(error, '状态修改失败')) }
  }

  const switchKind = (next: CanvasCreatorKind) => {
    setKind(next)
    setSelected(null)
    setDraft(null)
    setVersions([])
    setValidation(null)
  }

  return (
    <section className="studio-creator-workspace" aria-label={skillOnly ? '我的私人 Skill' : '我的私人 Agent / Skill'} aria-busy={loading}>
      <header className="studio-creator-workspace__intro">
        <div>
          <strong>{skillOnly ? '我的私人 Skill' : '我的私人 Agent / Skill'}</strong>
          <p>仅自己可见；发布版本后仍需加入可用业务分组才会出现在目录中。</p>
        </div>
        {!skillOnly ? <div className="studio-runtime-workspace__segment" role="tablist" aria-label="定义类型">
          <button type="button" role="tab" aria-selected={kind === 'skill'} className={kind === 'skill' ? 'is-active' : ''} onClick={() => switchKind('skill')}>Skill</button>
          <button type="button" role="tab" aria-selected={kind === 'agent'} className={kind === 'agent' ? 'is-active' : ''} onClick={() => switchKind('agent')}>Agent</button>
        </div> : null}
      </header>
      <div className="studio-creator-workspace__create">
        <input aria-label="能力编码" placeholder="英文编码，如 my-writing-skill" value={code} onChange={(event) => setCode(event.target.value)} />
        <input aria-label="能力名称" placeholder="名称" value={name} onChange={(event) => setName(event.target.value)} />
        <input aria-label="能力说明" placeholder="说明（可选）" value={description} onChange={(event) => setDescription(event.target.value)} />
        <button type="button" className="studio-runtime-workspace__primary" disabled={loading} onClick={() => void create()}>创建</button>
      </div>
      <div className="studio-creator-workspace__layout">
        <aside className="studio-runtime-workspace__rail" aria-label="我的能力列表">
          {items.length ? items.map((item) => (
            <button
              key={item.id}
              type="button"
              className={selected?.id === item.id ? 'is-active' : ''}
              onClick={() => void selectDefinition(item)}
            >
              <strong>{item.name}</strong>
              <small>{item.skillCode || item.agentCode}</small>
            </button>
          )) : <p className="studio-runtime-workspace__empty">尚无私人能力</p>}
        </aside>
        <div className="studio-creator-workspace__editor">
          {selected ? <>
            <div className="studio-creator-workspace__heading">
              <strong>{selected.name}</strong>
              <span className={`studio-runtime-workspace__status is-${selected.status === '0' ? 'ready' : 'idle'}`}>
                {selected.status === '0' ? '启用' : '停用'}
              </span>
              <button type="button" className="studio-runtime-workspace__ghost" onClick={() => void toggleStatus()}>
                {selected.status === '0' ? '停用' : '启用'}
              </button>
            </div>
            <p className="studio-runtime-workspace__hint">草稿定义字段按服务端契约编辑；保存后校验，再发布版本。</p>
            <textarea
              aria-label="草稿定义 JSON"
              className="studio-creator-workspace__json"
              value={draftText}
              onChange={(event) => { setDraftText(event.target.value); setDirty(true); setValidation(null) }}
              rows={13}
              spellCheck={false}
            />
            {validation?.errors?.length ? (
              <div className="studio-creator-workspace__errors" role="alert">
                {validation.errors.map((item, index) => <div key={`${item.field}-${index}`}>{item.field}：{item.message}</div>)}
              </div>
            ) : null}
            <div className="studio-creator-workspace__actions">
              <button type="button" className="studio-runtime-workspace__ghost" onClick={() => void validate()}>校验</button>
              <button type="button" className="studio-runtime-workspace__primary" onClick={() => void save()}>保存草稿</button>
              <input aria-label="版本号" value={versionCode} onChange={(event) => setVersionCode(event.target.value)} />
              <button type="button" className="studio-runtime-workspace__ghost" disabled={!draft?.draftId || dirty} onClick={() => void publish()}>发布版本</button>
            </div>
            {versions.length ? (
              <div className="studio-creator-workspace__versions">
                <strong>已发布版本</strong>
                {versions.map((version) => (
                  <div key={version.id}>
                    <span>{version.versionCode}{String(selected.currentVersionId) === String(version.id) ? ' · 当前' : ''}</span>
                    <button
                      type="button"
                      className="studio-runtime-workspace__ghost"
                      disabled={String(selected.currentVersionId) === String(version.id)}
                      onClick={() => void activate(version.id)}
                    >设为当前</button>
                  </div>
                ))}
              </div>
            ) : null}
          </> : (
            <p className="studio-runtime-workspace__empty">选择左侧能力以编辑草稿与版本。</p>
          )}
        </div>
      </div>
    </section>
  )
}
