'use client'

import { useState } from 'react'
import type { CanvasSkillDraft, CanvasSkillResource } from '@/types/canvas-skill-api'
import type { UserModelListItem } from '@/types/business-api'
import { StudioIconGlyph } from '@/components/studio/theme/StudioIconGlyph'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { skillReferencePathValid } from '@/utils/studio/canvasSkillAuthoring'

const fileLabels = {
  skillMarkdown: 'SKILL.md',
  systemPrompt: 'system_prompt.md',
  settings: '模型与契约'
}

const inputModeLabels = new Map([
  ['NONE', '无业务输入'],
  ['FREE_FORM', '自由文本或附件'],
  ['STRUCTURED', '结构化输入']
])

function asContract(value: unknown): Record<string, unknown> | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return undefined
  return value as Record<string, unknown>
}

function ContractFields({ schema }: { schema: Record<string, unknown> }) {
  const properties = asContract(schema.properties)
  const fields = Object.entries(properties ?? {})
  const items = asContract(schema.items)
  const required = Array.isArray(schema.required) ? schema.required : []
  if (!fields.length && !items) return null
  return (
    <ul className="studio-skill-contract__fields">
      {fields.map(([name, field]) => {
        const child = asContract(field) ?? {}
        return (
          <li key={name}>
            <code>{name}</code> · {String(child.type ?? '按契约')}
            {required.includes(name) ? ' · 必填' : ''}
            {typeof child.description === 'string' ? <div>{child.description}</div> : null}
            {Array.isArray(child.enum) ? <div>枚举 {JSON.stringify(child.enum)}</div> : null}
            <ContractFields schema={child} />
          </li>
        )
      })}
      {items ? <li>数组元素 · {String(items.type ?? '按契约')}<ContractFields schema={items} /></li> : null}
    </ul>
  )
}

function DraftContract({ label, value, disabled, onChange }: {
  label: string
  value?: string | null
  disabled: boolean
  onChange: (value: string) => void
}) {
  const [unlocked, setUnlocked] = useState(false)
  let schema: Record<string, unknown> | undefined
  try {
    schema = asContract(JSON.parse(value?.trim() || '{}'))
  } catch {
    // Keep invalid in-progress text available so the user can correct it before saving.
  }
  const examples = Array.isArray(schema?.examples) ? schema.examples : []
  const inputMode = inputModeLabels.get(String(schema?.['x-aid-input-mode'] ?? ''))
  return (
    <section className="studio-skill-contract" aria-label={label + '参数与样例'}>
      <h4>{label}参数</h4>
      {!schema ? <p role="alert">契约必须是有效的 JSON 对象。</p> : <>
        {inputMode ? <p>{inputMode}</p> : null}
        {typeof schema.type === 'string' ? <p>类型 {schema.type}</p> : null}
        <ContractFields schema={schema} />
        {!inputMode && !Object.keys(schema).some((key) => !['examples', 'title', 'description'].includes(key))
          ? <p>未声明结构化字段</p> : null}
      </>}
      <h4>{label}样例</h4>
      {examples.length ? <ol>{examples.map((example, index) => (
        <li key={index}><pre>{JSON.stringify(example, null, 2)}</pre></li>
      ))}</ol> : <p>暂无样例</p>}
      <button type="button" disabled={disabled} aria-expanded={unlocked && !disabled}
        onClick={() => setUnlocked(!unlocked)}>
        {unlocked && !disabled ? '锁定' : '解锁'}{label}参数与样例
      </button>
      {unlocked && !disabled ? <label>{label}契约与样例 JSON
        <textarea spellCheck={false} value={value ?? ''} onChange={(event) => onChange(event.target.value)} />
      </label> : null}
    </section>
  )
}

export function StudioSkillDraftEditor({
  draft,
  onChange,
  disabled,
  models
}: {
  draft: CanvasSkillDraft
  onChange: (next: CanvasSkillDraft) => void
  disabled: boolean
  models: UserModelListItem[]
}) {
  const [file, setFile] = useState('skillMarkdown')
  const [path, setPath] = useState('')
  const [pathError, setPathError] = useState('')
  const [modelOpen, setModelOpen] = useState(false)
  const resources = draft.resources || []
  const index = file.startsWith('reference:') ? Number(file.slice(10)) : -1
  const resource = resources[index]
  const patch = (changes: Partial<CanvasSkillDraft>) => onChange({ ...draft, ...changes })
  const patchResource = (changes: Partial<CanvasSkillResource>) =>
    patch({
      resources: resources.map((item, i) => (i === index ? { ...item, ...changes } : item))
    })
  const addFile = () => {
    const filePath = path.trim()
    if (!skillReferencePathValid(filePath)) {
      setPathError('使用 references/ 下的 .md 或 .txt 路径，不包含空目录或 ..')
      return
    }
    if (resources.some((item) => item.filePath === filePath)) {
      setPathError('该路径已经存在')
      return
    }
    if (resources.length >= 64) {
      setPathError('最多 64 个参考文件')
      return
    }
    let number = resources.length + 1
    while (resources.some((item) => item.resourceKey === `reference_${number}`)) number++
    patch({
      resources: [
        ...resources,
        {
          resourceKey: `reference_${number}`,
          filePath,
          content: '',
          resourceType: 'REFERENCE',
          mimeType: filePath.endsWith('.txt') ? 'text/plain' : 'text/markdown',
          routeJson: '{}'
        }
      ]
    })
    setFile(`reference:${resources.length}`)
    setPath('')
    setPathError('')
  }
  return (
    <div className="studio-skill-files">
      <nav className="studio-skill-files__tree" aria-label="Skill 文件">
        <small>主文件</small>
        {Object.entries(fileLabels).map(([key, label]) => (
          <StudioCanvasTooltip key={key} title={label}>
            <button
              type="button"
              aria-current={file === key ? 'page' : undefined}
              onClick={() => setFile(key)}
            >
              <StudioIconGlyph name={key === 'settings' ? 'skillSettings' : 'nodeText'} />
              <span>{label}</span>
            </button>
          </StudioCanvasTooltip>
        ))}
        <small>参考文件 · {resources.length}</small>
        {resources.map((item, i) => (
          <StudioCanvasTooltip key={item.resourceKey} title={item.filePath}>
            <button
              type="button"
              aria-current={index === i ? 'page' : undefined}
              onClick={() => setFile(`reference:${i}`)}
            >
              <StudioIconGlyph name="library" />
              <span>{item.filePath}</span>
            </button>
          </StudioCanvasTooltip>
        ))}
        <label className="studio-skill-add-file">
          新增参考文件
          <input
            disabled={disabled}
            value={path}
            onChange={(event) => {
              setPath(event.target.value)
              setPathError('')
            }}
            placeholder="references/method.md"
          />
        </label>
        <button type="button" disabled={disabled || !path.trim()} onClick={addFile}>
          添加文件
        </button>
        {pathError ? <p role="alert">{pathError}</p> : null}
        <small>aid-skill.json 在发布时自动生成</small>
      </nav>
      <div className="studio-skill-files__content">
        <header>
          <strong>
            {resource
              ? resource.filePath
              : fileLabels[file as keyof typeof fileLabels] || '请选择文件'}
          </strong>
          {resource ? (
            <button
              type="button"
              disabled={disabled}
              onClick={() => {
                patch({ resources: resources.filter((_, i) => i !== index) })
                setFile('skillMarkdown')
              }}
            >
              移除此参考文件
            </button>
          ) : (
            <span>草稿 · 修改后保存</span>
          )}
        </header>
        {file === 'settings' ? (
          <div className="studio-skill-settings">
            <label>
              默认模型
              <StudioCanvasDropdownPopover
                ariaLabel="默认模型"
                open={modelOpen && !disabled}
                onOpenChange={setModelOpen}
                placement="bottomLeft"
                items={[
                  ...models.map((model) => ({
                    key: model.modelCode,
                    label: model.modelName || model.modelCode
                  })),
                  ...(draft.defaultModelCode &&
                  !models.some((model) => model.modelCode === draft.defaultModelCode)
                    ? [
                        {
                          key: draft.defaultModelCode,
                          label: `${draft.defaultModelCode}（原配置，待校验）`
                        }
                      ]
                    : [])
                ].map((item) => ({ ...item, disabled }))}
                activeKey={draft.defaultModelCode}
                onSelect={(code) =>
                  patch({
                    defaultModelCode: code,
                    modelCode: code,
                    selectableModelCodes: [
                      ...new Set([...(draft.selectableModelCodes || []), code])
                    ]
                  })
                }
                trigger={
                  <button type="button" aria-label="默认模型" disabled={disabled}>
                    {models.find((model) => model.modelCode === draft.defaultModelCode)
                      ?.modelName ||
                      draft.defaultModelCode ||
                      '选择模型'}
                  </button>
                }
              />
            </label>
            <fieldset disabled={disabled}>
              <legend>可选模型</legend>
              <div className="studio-skill-models">
                {[
                  ...new Set([
                    ...models.map((model) => model.modelCode),
                    ...(draft.selectableModelCodes || [])
                  ])
                ].map((code) => (
                  <label key={code}>
                    <input
                      type="checkbox"
                      checked={draft.selectableModelCodes?.includes(code) || false}
                      onChange={(event) =>
                        patch({
                          selectableModelCodes: event.target.checked
                            ? [...(draft.selectableModelCodes || []), code]
                            : (draft.selectableModelCodes || []).filter((item) => item !== code)
                        })
                      }
                    />
                    {models.find((model) => model.modelCode === code)?.modelName || code}
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="studio-skill-token-fields">
              {(['maxOutputTokens', 'contextWindowTokens', 'safetyMarginTokens'] as const).map(
                (key, i) => (
                  <label key={key}>
                    {['输出上限', '上下文容量', '预留容量'][i]}
                    <input
                      disabled={disabled}
                      type="number"
                      value={draft[key] ?? ''}
                      onChange={(event) =>
                        patch({
                          [key]: event.target.value ? Number(event.target.value) : undefined
                        })
                      }
                    />
                  </label>
                )
              )}
            </div>
            {(
              ['modelConfigJson', 'definitionJson'] as const
            ).map((key, i) => (
              <label key={key}>
                {['完整模型配置（保留扩展字段）', '执行定义'][i]}
                <textarea
                  disabled={disabled}
                  spellCheck={false}
                  value={draft[key] ?? ''}
                  onChange={(event) => patch({ [key]: event.target.value })}
                />
              </label>
            ))}
            <DraftContract key={`input:${draft.skillId}:${draft.draftId}`} label="输入"
              value={draft.inputSchemaJson} disabled={disabled} onChange={(inputSchemaJson) => patch({ inputSchemaJson })} />
            <DraftContract key={`output:${draft.skillId}:${draft.draftId}`} label="输出"
              value={draft.outputSchemaJson} disabled={disabled} onChange={(outputSchemaJson) => patch({ outputSchemaJson })} />
            <fieldset disabled={disabled}>
              <legend>固定子 Skill 依赖</legend>
              {(draft.relations || []).map((relation, i) => (
                <div className="studio-skill-dependency" key={i}>
                  {(['relationKey', 'childSkillId', 'childVersionId'] as const).map((key, j) => (
                    <label key={key}>
                      {['依赖键', 'Skill ID', '固定版本 ID'][j]}
                      <input
                        value={relation[key]}
                        onChange={(event) =>
                          patch({
                            relations: (draft.relations || []).map((item, index) =>
                              index === i ? { ...item, [key]: event.target.value } : item
                            )
                          })
                        }
                      />
                    </label>
                  ))}
                  <label>
                    <input
                      type="checkbox"
                      checked={relation.requiredFlag !== false}
                      onChange={(event) =>
                        patch({
                          relations: (draft.relations || []).map((item, index) =>
                            index === i
                              ? {
                                  ...item,
                                  requiredFlag: event.target.checked
                                }
                              : item
                          )
                        })
                      }
                    />
                    必需
                  </label>
                  <button
                    type="button"
                    onClick={() =>
                      patch({
                        relations: (draft.relations || []).filter((_, index) => index !== i)
                      })
                    }
                  >
                    移除依赖
                  </button>
                </div>
              ))}
              <button
                type="button"
                disabled={(draft.relations?.length || 0) >= 16}
                onClick={() =>
                  patch({
                    relations: [
                      ...(draft.relations || []),
                      {
                        relationKey: '',
                        childSkillId: '',
                        childVersionId: '',
                        requiredFlag: true
                      }
                    ]
                  })
                }
              >
                添加固定依赖
              </button>
              <small>仅引用有权读取的正式版本，公开时服务端再次校验可共享性。</small>
            </fieldset>
          </div>
        ) : resource ? (
          <>
            <div className="studio-skill-resource-meta">
              <label>
                资源键
                <input
                  disabled={disabled}
                  value={resource.resourceKey}
                  onChange={(event) => patchResource({ resourceKey: event.target.value })}
                />
              </label>
              <label>
                读取规则（JSON）
                <input
                  disabled={disabled}
                  value={resource.routeJson || '{}'}
                  onChange={(event) => patchResource({ routeJson: event.target.value })}
                />
              </label>
            </div>
            <textarea
              className="studio-skill-document"
              aria-label={`编辑 ${resource.filePath}`}
              disabled={disabled}
              spellCheck={false}
              value={resource.content}
              onChange={(event) => patchResource({ content: event.target.value })}
            />
          </>
        ) : (
          <textarea
            className="studio-skill-document"
            aria-label={`编辑 ${fileLabels[file as 'skillMarkdown' | 'systemPrompt']}`}
            disabled={disabled}
            spellCheck={false}
            value={file === 'systemPrompt' ? draft.systemPrompt || '' : draft.skillMarkdown || ''}
            onChange={(event) =>
              patch({
                [file === 'systemPrompt' ? 'systemPrompt' : 'skillMarkdown']: event.target.value
              })
            }
          />
        )}
      </div>
    </div>
  )
}
