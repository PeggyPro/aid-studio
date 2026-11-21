'use client'

import { Slider } from 'antd'
import { useRef, useState } from 'react'
import type { AudioClientContract, AudioSchema } from '@/types/audio-model'
import { audioConstraints, audioDefault, audioFieldContract, audioSchema, audioSchemaType, audioSet } from '@/utils/audioModelContract'
import type { ParameterConstraint } from '@/utils/modelParameterConstraints'
import { audioSettingLabel, projectAudioSettingGroups, projectAudioSettingSlice, type AudioSettingGroup } from '@/utils/studio/studioAudioSettingGroups'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import './studio-audio-capability.css'

export function StudioAudioCapabilityFields({ contract, value, errors, onChange, onUpload, disabled }: {
  contract: AudioClientContract
  value: Record<string, unknown>
  errors: Record<string, string>
  onChange: (next: Record<string, unknown>) => void
  onUpload: (path: string, file: File) => Promise<void>
  disabled?: boolean
}) {
  const constraints = audioConstraints(contract, value)
  const groups = projectAudioSettingGroups(contract, value, constraints)
  return <div className="studio-audio-capability nodrag nopan" role="group" aria-label="当前模型音频设置" onWheel={event => event.stopPropagation()}>
    {groups.map(group => <SettingGroup key={group.path} {...{ contract, group, value, errors, constraints, onChange, onUpload, disabled }} />)}
    {!!contract.minimumBillableSeconds && <p className="studio-audio-capability__hint">每个结果最低计费 {contract.minimumBillableSeconds} 秒</p>}
  </div>
}

function SettingGroup({ contract, group, value, errors, constraints, onChange, onUpload, disabled }: {
  contract: AudioClientContract
  group: AudioSettingGroup
  value: Record<string, unknown>
  errors: Record<string, string>
  constraints: Map<string, ParameterConstraint>
  onChange: (next: Record<string, unknown>) => void
  onUpload: (path: string, file: File) => Promise<void>
  disabled?: boolean
}) {
  const write = (next: unknown) => onChange(audioSet(value, group.path, next))
  const error = errors[group.path]
  const showTitle = !/^\d+$/.test(group.path.split('.').at(-1) ?? '')
  const title = <h4>{group.title}{group.required ? ' *' : ''}</h4>
  if (group.kind === 'segments' || group.kind === 'switch') {
    const options = group.kind === 'switch'
      ? [{ value: true, label: '开启' }, { value: false, label: '关闭' }]
      : group.options ?? []
    return       <section className="studio-params-panel__group studio-params-panel__group--segmented" data-audio-field={group.path}>
      {showTitle ? title : null}
      <div>{options.map(option => {
        const selected = group.value === option.value
        return <button type="button" key={String(option.value)} aria-pressed={selected} className={selected ? 'is-active' : ''} disabled={disabled}
          onClick={() => write(option.value)}>{option.label}</button>
      })}</div>
      {error && <p role="alert" className="studio-audio-capability__error">{error}</p>}
    </section>
  }
  if (group.kind === 'number' || group.kind === 'text') {
    const name = group.path.split('.').at(-1) ?? group.path
    const range = group.schema.minimum != null || group.schema.maximum != null
      ? [group.schema.minimum, group.schema.maximum].filter(item => item != null).join('–') : ''
    return <section className="studio-params-panel__group studio-params-panel__group--stack" data-audio-field={group.path}>
      {showTitle ? title : null}
      <div>{group.kind === 'number'
        ? <input type="number" inputMode="decimal" aria-label={group.title} value={group.value == null ? '' : String(group.value)}
          min={group.schema.minimum} max={group.schema.maximum} step={audioSchemaType(group.schema) === 'integer' ? 1 : 'any'} disabled={disabled}
          placeholder={range} aria-invalid={!!error}
          onChange={event => write(event.target.value === '' ? undefined : Number(event.target.value))} />
        : <textarea aria-label={group.title} rows={['lyrics', 'prompt', 'text'].includes(name) ? 3 : 2} value={group.value == null ? '' : String(group.value)}
          maxLength={group.schema.maxLength} disabled={disabled} placeholder={group.required ? '必填' : ''} aria-invalid={!!error}
          onChange={event => write(event.target.value || undefined)} />}</div>
      {error && <p role="alert" className="studio-audio-capability__error">{error}</p>}
    </section>
  }
  if (group.kind === 'slider') {
    const min = group.minimum ?? 0
    const max = group.maximum ?? min
    const neutral = typeof group.schema.default === 'number' ? group.schema.default : min <= 0 && max >= 0 ? 0 : min
    const current = typeof group.value === 'number' ? group.value : neutral
    const semitone = /(?:^|\.)pitch(?:_rate)?$/.test(group.path)
    return <section className="studio-params-panel__group studio-params-panel__group--stack" data-audio-field={group.path}>
      <h4>{group.title} · {current}{semitone ? ' 半音' : ''}{group.required ? ' *' : ''}</h4>
      <div><Slider min={min} max={max} step={audioSchemaType(group.schema) === 'integer' ? 1 : 0.1} value={current} disabled={disabled} onChange={next => write(next)} /></div>
      {error && <p role="alert" className="studio-audio-capability__error">{error}</p>}
    </section>
  }
  if (group.kind === 'menu') {
    const current = group.options?.find(option => option.value === group.value)
    return <section className="studio-params-panel__group studio-params-panel__group--stack" data-audio-field={group.path}>
      {showTitle ? title : null}
      <div><StudioCanvasDropdownPopover nodePlacement ariaLabel={group.title} activeKey={String(group.value ?? '')}
        items={(group.options ?? []).map(option => ({ key: String(option.value), label: option.label }))}
        onSelect={key => write(group.options?.find(option => String(option.value) === key)?.value)}
        trigger={<button type="button" disabled={disabled}>{current?.label ?? '请选择'}</button>} /></div>
      {error && <p role="alert" className="studio-audio-capability__error">{error}</p>}
    </section>
  }
  if (group.kind === 'list') {
    const items = Array.isArray(group.value) ? group.value : []
    const itemSchema = group.schema.items ?? { type: 'string' }
    return <section className="studio-params-panel__group studio-params-panel__group--stack" data-audio-field={group.path}>
      {showTitle ? title : null}
      <div className="studio-audio-settings__entries">
        {items.map((_, index) => <div key={index} className="studio-audio-settings__entry">
          {projectAudioSettingSlice(contract, value, itemSchema, `${group.path}.${index}`, constraints).map(child =>
            <SettingGroup key={child.path} {...{ contract, value, errors, constraints, onChange, onUpload, disabled }} group={child} />)}
          <button type="button" disabled={disabled} onClick={() => write(items.filter((__, position) => position !== index).length
            ? items.filter((__, position) => position !== index) : undefined)}>移除</button>
        </div>)}
        <button type="button" disabled={disabled || items.length >= (group.schema.maxItems ?? 100)}
          onClick={() => write([...items, audioDefault(itemSchema, contract.schema)])}>添加</button>
      </div>
      {error && <p role="alert" className="studio-audio-capability__error">{error}</p>}
    </section>
  }
  return <AudioField contract={contract} schema={group.schema} path={group.path} value={group.value} required={group.required}
    errors={errors} constraints={constraints} onChange={write} onUpload={onUpload} disabled={disabled} />
}

function AudioField({ contract, schema: raw, path, value, required, errors, constraints, onChange, onUpload, disabled, depth = 0 }: {
  contract: AudioClientContract; schema: AudioSchema; path: string; value: unknown; required: boolean
  errors: Record<string, string>; onChange: (value: unknown) => void
  constraints: Map<string, ParameterConstraint>
  onUpload: (path: string, file: File) => Promise<void>; disabled?: boolean; depth?: number
}) {
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState('')
  const [chosenBranch, setChosenBranch] = useState<{ value: unknown; index: number } | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const baseSchema = audioSchema(raw, contract.schema)
  const constraint = constraints.get(`options.${path}`)
  const schema = constraint?.choices ? { ...baseSchema, enum: constraint.choices.filter(option => !baseSchema.enum || baseSchema.enum.includes(option)) } : baseSchema
  if (depth > 12 || contract.managedFields.includes(path) || contract.promptPath === path) return null
  if (constraint?.forbidden && value == null) return null
  required ||= constraint?.required === true
  const name = path.split('.').at(-1) ?? path
  const label = audioSettingLabel(name, schema)
  const file = audioFieldContract(contract, path)
  const type = audioSchemaType(schema)
  const options = schema.enum ?? contract.sharedChoices[name]
  const alternative = schema.oneOf ?? schema.anyOf
  const inferredBranch = alternative?.findIndex(candidate => {
    const item = audioSchema(candidate, contract.schema)
    const kind = audioSchemaType(item)
    if (kind === 'object') return value != null && typeof value === 'object' && !Array.isArray(value)
      && (item.required ?? []).every(key => Object.hasOwn(value, key))
      && Object.entries(item.properties ?? {}).every(([key, field]) => field.const == null || (value as Record<string, unknown>)[key] === field.const)
    if (kind === 'array') return Array.isArray(value)
    return typeof value === (kind === 'integer' ? 'number' : kind) && (!item.enum || item.enum.includes(value))
  }) ?? -1
  const branchIndex = chosenBranch && chosenBranch.value === value ? chosenBranch.index : Math.max(0, inferredBranch)
  const id = `audio-param-${path.replaceAll('.', '-')}`
  const error = uploadError || errors[path]
  const update = (next: unknown) => { setUploadError(''); onChange(next) }
  const disabledField = disabled || uploading
  const upload = async (selected: File) => {
    setUploading(true); setUploadError('')
    try {
      if (!file) throw new Error('当前参数不支持附件')
      const extension = selected.name.split('.').at(-1)?.toLowerCase()
      if (file.formats?.length && !file.formats.includes(extension ?? '')) throw new Error(`支持格式：${file.formats.join('、')}`)
      if (file.maxFileSizeMb != null && selected.size > file.maxFileSizeMb * 1024 * 1024) throw new Error(`文件不能超过 ${file.maxFileSizeMb} MB`)
      await onUpload(path, selected)
    } catch (reason) { setUploadError(reason instanceof Error ? reason.message : '附件上传失败') }
    finally { setUploading(false) }
  }
  if (name.endsWith('_data') || name.endsWith('_base64')) return null
  // 时间线在界面以分段表单编辑，只有协议层需要 JSON 编码字符串。
  if (name === 'segments' && type === 'string') {
    let segments: unknown[] = []
    try { segments = value ? JSON.parse(String(value)) : [] } catch { /* 保留输入并由服务端拒绝。 */ }
    const segmentSchema: AudioSchema = { type: 'array', maxItems: 30, items: { type: 'object', required: ['start', 'prompt'], properties: {
      start: { type: 'number', minimum: 0 }, prompt: { type: 'string', minLength: 1, maxLength: 200 },
      label: { type: 'string', enum: ['intro', 'verse', 'pre-chorus', 'chorus', 'bridge', 'break', 'silence', 'outro', 'none'] }
    } } }
    return <AudioField {...{ contract, path, required, errors, constraints, onUpload, disabled, depth }} schema={segmentSchema}
      value={segments} onChange={next => update(Array.isArray(next) && next.length ? JSON.stringify(next) : undefined)} />
  }
  return <section className={`studio-audio-capability__field${options?.length || type === 'boolean' || type === 'number' || type === 'integer' ? ' is-inline' : ''}${type === 'object' || type === 'array' ? ' is-structured' : ''}`} data-audio-field={path}>
    <div className="studio-audio-capability__label"><StudioCanvasTooltip title={schema.description || label}><label htmlFor={id}>{label}{required ? ' *' : ''}</label></StudioCanvasTooltip></div>
    {file ? <div className="studio-audio-capability__file">
      {file.mediaType === 'AUDIO' ? <span id={id} className="studio-audio-capability__attachment-status">{value ? '音频已就绪 · 可从右上角更换' : '请使用输入框右上角的附件按钮上传音频'}</span> : <><button type="button" id={id} onClick={() => inputRef.current?.click()} disabled={disabledField}>{uploading ? '上传并验证中…' : value ? '更换附件' : '上传附件'}</button>
      <input ref={inputRef} type="file" hidden accept={file.formats?.map(format => `.${format}`).join(',') ?? `${file.mediaType.toLowerCase()}/*`}
        onChange={event => { const selected = event.target.files?.[0]; event.target.value = ''; if (selected) void upload(selected) }} />
      {value != null && <span>已选择附件</span>}</>}
      <small>{file.formats?.join(' / ')}{file.maxFileSizeMb ? ` · ≤${file.maxFileSizeMb} MB` : ''}
        {file.minDurationSeconds ? ` · ≥${file.minDurationSeconds}秒` : ''}{file.maxDurationSeconds ? ` · ≤${file.maxDurationSeconds}秒` : ''}
        {file.clipDurationSeconds ? ` · 官方截取${file.clipDurationSeconds}秒` : ''}</small>
    </div> : alternative?.length ? <>
      <StudioCanvasDropdownPopover nodePlacement ariaLabel={label} activeKey={String(branchIndex)}
        items={alternative.map((option, index) => ({ key: String(index), label: option.title ?? `${label}方案 ${index + 1}` }))}
        onSelect={key => { const index = Number(key); const next = audioDefault(alternative[index], contract.schema); setChosenBranch({ value: next, index }); update(next) }}
        trigger={<button type="button" id={id} disabled={disabledField}>{alternative[branchIndex]?.title ?? `方案 ${branchIndex + 1}`}</button>} />
      <AudioField {...{ contract, path, value, required, errors, constraints, onChange: update, onUpload, disabled, depth: depth + 1 }} schema={alternative[branchIndex] ?? alternative[0]} />
    </> : type === 'object' ? <div className="studio-audio-capability__nested">
      {Object.entries(schema.properties ?? {}).map(([key, child]) => <AudioField key={key} contract={contract} schema={child}
          path={`${path}.${key}`} value={(value as Record<string, unknown> | undefined)?.[key]} required={schema.required?.includes(key) ?? false}
          errors={errors} constraints={constraints} onChange={next => update(audioSet((value ?? {}) as Record<string, unknown>, key, next))} onUpload={onUpload} disabled={disabledField} depth={depth + 1} />)}
    </div> : type === 'array' ? <div className="studio-audio-capability__nested">
      {(Array.isArray(value) ? value : []).map((item, index) => <div key={index} className="studio-audio-capability__item">
        <AudioField {...{ contract, required: true, errors, constraints, onUpload, disabled: disabledField, depth: depth + 1 }} schema={schema.items ?? { type: 'string' }}
          path={`${path}.${index}`} value={item} onChange={next => update((value as unknown[]).map((old, position) => position === index ? next : old))} />
        <button type="button" disabled={disabledField} aria-label={`移除${label}第${index + 1}项`} onClick={() => update((value as unknown[]).filter((_, position) => position !== index))}>移除</button>
      </div>)}
      <button type="button" disabled={disabledField || (Array.isArray(value) ? value.length : 0) >= (schema.maxItems ?? 100)}
        onClick={() => update([...(Array.isArray(value) ? value : []), audioDefault(schema.items ?? { type: 'string' }, contract.schema)])}>添加{label}</button>
    </div> : options?.length && options.length <= 6 ? <div className="studio-audio-capability__segments" role="group" aria-label={label}>
      {options.map((item, index) => <button type="button" key={String(item)} id={index === 0 ? id : undefined} disabled={disabledField} aria-pressed={value === item}
        onClick={() => update(item)}>{contract.choiceLabels?.[name]?.[String(item)] ?? String(item)}</button>)}
    </div> : options?.length ? <StudioCanvasDropdownPopover nodePlacement ariaLabel={label} activeKey={String(value ?? '')}
      items={options.map(item => ({ key: String(item), label: contract.choiceLabels?.[name]?.[String(item)] ?? String(item) }))}
      onSelect={key => update(options.find(item => String(item) === key))}
      trigger={<button type="button" id={id} disabled={disabledField}>{value == null ? '请选择' : contract.choiceLabels?.[name]?.[String(value)] ?? String(value)}</button>} />
      : type === 'boolean' ? <button type="button" role="switch" id={id} aria-checked={value === true} disabled={disabledField}
        onClick={() => update(value !== true)}>{value === true ? '开启' : '关闭'}</button>
      : type === 'number' || type === 'integer' ? <input id={id} type="number" inputMode="decimal" value={value == null ? '' : String(value)}
        min={schema.minimum} max={schema.maximum} step={type === 'integer' ? 1 : 'any'} disabled={disabledField}
        aria-invalid={!!error} aria-describedby={error ? `${id}-error` : undefined}
        onChange={event => update(event.target.value === '' ? undefined : Number(event.target.value))} />
      : <textarea id={id} rows={['lyrics', 'prompt', 'text'].includes(name) ? 3 : 1} value={value == null ? '' : String(value)}
        maxLength={schema.maxLength} disabled={disabledField} aria-invalid={!!error} aria-describedby={error ? `${id}-error` : undefined}
        onChange={event => update(event.target.value || undefined)} placeholder={required ? '必填' : ''} />}
    {error && <p id={`${id}-error`} role="alert" className="studio-audio-capability__error">{error}</p>}
  </section>
}
