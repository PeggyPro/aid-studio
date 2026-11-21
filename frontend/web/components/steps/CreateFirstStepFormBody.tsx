'use client'

import { useEffect, useRef, useState } from 'react'
import { Tooltip } from 'antd'
import GlobalSetting from '~/components/steps/GlobalSetting'
import type { GlobalSettingData } from '~/types'
import { CREATE_FIRST_STEP_DEFAULTS } from '~/utils/createFirstStepDefaults'
import { DICT_ENUM, usePromptDictionary } from '~/composables/usePromptDictionary'
import {
  buildEnumChipOptions,
  normalizeCreationModeValue,
  normalizeModelStrategyValue,
  normalizeScriptTypeValue,
  type GlobalSettingCreationMode,
  type GlobalSettingModelStrategy,
  type GlobalSettingScriptType
} from '~/utils/globalSettingEnums'
import {
  isCreationModeDisabledForScriptType,
  isScriptTypeLockedToPlot,
  PRO_MODE_NO_STORYBOARD_IMAGE_TIP,
  resolveCreationModeForScriptType,
  skipsStoryboardImageGeneration
} from '~/utils/creationModeUiRules'
import unionLeftIconMod from '~/assets/img/createProcess/Union-l.svg'
import unionRightIconMod from '~/assets/img/createProcess/Union-r.svg'
import { SERIES_PROJECT_CONFIG_STORYBOARD_LOCKED_HINT } from '~/utils/seriesProjectConfigGuard'
import {
  PROJECT_CONFIG_ASSETS_LOCKED_HINT,
  PROJECT_CONFIG_ASSETS_STYLE_LOCK_HINT
} from '~/utils/projectConfigAssetGuard'
import { assetUrl } from '~/utils/assetUrl'
import './create-first-step-form-body.css'

const unionLeftIcon = assetUrl(unionLeftIconMod)
const unionRightIcon = assetUrl(unionRightIconMod)

type RatioValue = '16:9' | '9:16' | '4:3' | '3:4' | '1:1' | '21:9'
type ProjectTypeValue = 'movie' | 'series'
type ScriptTypeValue = GlobalSettingScriptType
type StrategyValue = GlobalSettingModelStrategy
type CreationModeValue = GlobalSettingCreationMode

export interface CreateFirstStepFormBodyProps {
  open: boolean
  title: string
  projectType: ProjectTypeValue
  aspectRatio: RatioValue
  scriptType: ScriptTypeValue
  modelStrategy: StrategyValue
  creationMode: CreationModeValue
  modelValue: GlobalSettingData
  flowEditMode?: boolean
  projectTypeLocked?: boolean
  contentConfigLocked?: boolean
  contentConfigLockReason?: 'storyboard' | 'assets'
  syncProjectTypeFromParent?: boolean
  hideProjectType?: boolean
  /** 页面内嵌布局（非弹窗） */
  pageLayout?: boolean
  /** 原 Vue 组件外部 class 透传（弹窗侧 modal-body） */
  className?: string
  onTitleChange: (value: string) => void
  onProjectTypeChange: (value: ProjectTypeValue) => void
  onAspectRatioChange: (value: RatioValue) => void
  onScriptTypeChange: (value: ScriptTypeValue) => void
  onModelStrategyChange: (value: StrategyValue) => void
  onCreationModeChange: (value: CreationModeValue) => void
  onModelValueChange: (value: GlobalSettingData) => void
}

type EnumOption = { value: string; label: string }

const ratioIconByValue: Record<RatioValue, { width:number; height:number; label:string }> = {
  '16:9': {width:18,height:10.125,label:'16 : 9'}, '9:16': {width:10.125,height:18,label:'9 : 16'},
  '4:3': {width:18,height:13.5,label:'4 : 3'}, '3:4': {width:13.5,height:18,label:'3 : 4'},
  '1:1': {width:16,height:16,label:'1 : 1'}, '21:9': {width:21,height:9,label:'21 : 9'}
}

function groupOptions(
  groups: { enumType: string; items: { value: string; desc: string }[] }[],
  enumType: string
): EnumOption[] {
  return buildEnumChipOptions(groups, enumType)
}

const PROJECT_TYPE_ORDER: ProjectTypeValue[] = ['series', 'movie']

function sortOptionsByOrder<T extends { value: string }>(
  options: T[],
  order: readonly string[]
): T[] {
  return [...options].sort((a, b) => {
    const ai = order.indexOf(a.value)
    const bi = order.indexOf(b.value)
    return (ai === -1 ? order.length : ai) - (bi === -1 ? order.length : bi)
  })
}

function ensureEnumSelection<T extends string>(
  current: T,
  options: Array<{ value: T }>,
  emitUpdate: (v: T) => void,
  normalize?: (raw: string) => T | null
) {
  if (!options.length) return
  const normalized = normalize ? normalize(String(current)) : null
  if (normalized && options.some((o) => o.value === normalized)) {
    if (normalized !== current) emitUpdate(normalized)
    return
  }
  if (options.some((o) => o.value === current)) return
  emitUpdate(options[0]!.value)
}

/** DOM 更新后再继续（原 await nextTick） */
function nextFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()))
}

/** 创建作品第一步表单主体（左侧基本信息 + 右侧风格库），原 CreateFirstStepFormBody.vue */
export function CreateFirstStepFormBody({
  open,
  title,
  projectType,
  aspectRatio,
  scriptType,
  modelStrategy,
  creationMode,
  modelValue,
  flowEditMode = false,
  projectTypeLocked = false,
  contentConfigLocked = false,
  contentConfigLockReason,
  syncProjectTypeFromParent = false,
  hideProjectType = false,
  pageLayout = false,
  className,
  onTitleChange,
  onProjectTypeChange,
  onAspectRatioChange,
  onScriptTypeChange,
  onModelStrategyChange,
  onCreationModeChange,
  onModelValueChange
}: CreateFirstStepFormBodyProps) {
  // 事件回调 / async 流程读最新 props（等价于 Vue props 响应式引用）
  const propsRef = useRef({ open, projectType, flowEditMode, pageLayout, syncProjectTypeFromParent })
  propsRef.current = { open, projectType, flowEditMode, pageLayout, syncProjectTypeFromParent }
  const emitRef = useRef({
    onTitleChange,
    onProjectTypeChange,
    onAspectRatioChange,
    onScriptTypeChange,
    onModelStrategyChange,
    onCreationModeChange,
    onModelValueChange
  })
  emitRef.current = {
    onTitleChange,
    onProjectTypeChange,
    onAspectRatioChange,
    onScriptTypeChange,
    onModelStrategyChange,
    onCreationModeChange,
    onModelValueChange
  }

  const [projectTypeOptions, setProjectTypeOptions] = useState<
    Array<{ value: ProjectTypeValue; label: string }>
  >([
    { value: 'series', label: '剧集' },
    { value: 'movie', label: '电影' }
  ])

  const [scriptTypeOptions, setScriptTypeOptions] = useState<
    Array<{ value: ScriptTypeValue; label: string }>
  >([])
  const [creationModeOptions, setCreationModeOptions] = useState<
    Array<{ value: CreationModeValue; label: string }>
  >([])
  const [modelStrategyOptions, setModelStrategyOptions] = useState<
    Array<{ value: StrategyValue; label: string }>
  >([])
  const [enumOptionsLoaded, setEnumOptionsLoaded] = useState(false)

  const [ratioOptions, setRatioOptions] = useState<
    Array<{ label: string; value: RatioValue; width:number; height:number }>
  >(() =>
    Object.entries(ratioIconByValue).map(([value, meta]) => ({
      value: value as RatioValue,
      label: meta.label,
      width:meta.width,
      height:meta.height
    }))
  )

  const isNewProjectModal = !flowEditMode
  const isNewProjectModalRef = useRef(isNewProjectModal)
  isNewProjectModalRef.current = isNewProjectModal
  const [leftPanelReady, setLeftPanelReady] = useState(false)

  const contentReady = open && (flowEditMode || pageLayout || leftPanelReady)

  const [leftPanelDraft, setLeftPanelDraftState] = useState({
    projectType: CREATE_FIRST_STEP_DEFAULTS.projectType as ProjectTypeValue,
    scriptType: CREATE_FIRST_STEP_DEFAULTS.scriptType as ScriptTypeValue,
    modelStrategy: CREATE_FIRST_STEP_DEFAULTS.modelStrategy as StrategyValue,
    creationMode: CREATE_FIRST_STEP_DEFAULTS.creationMode as CreationModeValue
  })
  const leftPanelDraftRef = useRef(leftPanelDraft)

  function patchLeftPanelDraft(patch: Partial<typeof leftPanelDraft>) {
    const next = { ...leftPanelDraftRef.current, ...patch }
    leftPanelDraftRef.current = next
    setLeftPanelDraftState(next)
  }

  const displayProjectType = isNewProjectModal ? leftPanelDraft.projectType : projectType
  const displayScriptType = isNewProjectModal ? leftPanelDraft.scriptType : scriptType
  const displayModelStrategy = isNewProjectModal ? leftPanelDraft.modelStrategy : modelStrategy
  const displayCreationMode = isNewProjectModal ? leftPanelDraft.creationMode : creationMode

  const { ensureLoaded: ensureDictLoaded, loaded: dictLoaded, enumList } = usePromptDictionary()

  function resetLeftPanelDraft() {
    const d = CREATE_FIRST_STEP_DEFAULTS
    patchLeftPanelDraft({
      projectType: d.projectType,
      scriptType: d.scriptType,
      modelStrategy: d.modelStrategy,
      creationMode: d.creationMode
    })
  }

  function applyParentProjectType() {
    if (!propsRef.current.syncProjectTypeFromParent) return
    const pt = propsRef.current.projectType
    if (pt === 'series' || pt === 'movie') {
      patchLeftPanelDraft({ projectType: pt })
    }
  }

  function syncLeftPanelToParent() {
    emitRef.current.onProjectTypeChange(leftPanelDraftRef.current.projectType)
    emitRef.current.onScriptTypeChange(leftPanelDraftRef.current.scriptType)
    emitRef.current.onModelStrategyChange(leftPanelDraftRef.current.modelStrategy)
    emitRef.current.onCreationModeChange(leftPanelDraftRef.current.creationMode)
  }

  function initNewProjectLeftPanel() {
    resetLeftPanelDraft()
    applyParentProjectType()
    syncLeftPanelToParent()
  }
  const initNewProjectLeftPanelRef = useRef(initNewProjectLeftPanel)
  initNewProjectLeftPanelRef.current = initNewProjectLeftPanel

  /** 复用风格库同一套字典缓存，与 GlobalSetting.ensureLoaded 合并为一次 enum/list */
  // 原 loadCreateModalEnums：onMounted 触发 ensureLoaded，字典就绪后组装枚举选项（React 侧拆为两个 effect）
  useEffect(() => {
    void ensureDictLoaded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const enumsInitDoneRef = useRef(false)
  useEffect(() => {
    if (!dictLoaded) return
    const groups = enumList

    const projectTypes = groupOptions(groups, DICT_ENUM.ProjectTypeEnum)
      .map((o) => {
        if (o.value === 'series') return { value: 'series' as const, label: '剧集' }
        if (o.value === 'movie') return { value: 'movie' as const, label: '电影' }
        return null
      })
      .filter(Boolean) as Array<{ value: ProjectTypeValue; label: string }>
    if (projectTypes.length) {
      setProjectTypeOptions(sortOptionsByOrder(projectTypes, PROJECT_TYPE_ORDER))
    }

    const aspectRatios = groupOptions(groups, DICT_ENUM.AspectRatioEnum)
      .map((o) => (o.value in ratioIconByValue ? (o.value as RatioValue) : null))
      .filter(Boolean) as RatioValue[]
    if (aspectRatios.length) {
      setRatioOptions(
        aspectRatios.map((v) => {
          const meta = ratioIconByValue[v]
          return { value: v, label: meta.label, width:meta.width, height:meta.height }
        })
      )
    }

    const scripts = groupOptions(groups, DICT_ENUM.ScriptTypeEnum)
      .map((o) => {
        const value = normalizeScriptTypeValue(o.value)
        return value ? { value, label: o.label } : null
      })
      .filter(Boolean) as Array<{ value: ScriptTypeValue; label: string }>
    if (scripts.length) setScriptTypeOptions(scripts)

    const creations = groupOptions(groups, DICT_ENUM.CreationModeEnum)
      .map((o) => {
        const value = normalizeCreationModeValue(o.value)
        return value ? { value, label: o.label } : null
      })
      .filter(Boolean) as Array<{ value: CreationModeValue; label: string }>
    if (creations.length) setCreationModeOptions(creations)

    const genModes = groupOptions(groups, DICT_ENUM.GenModeEnum)
      .map((o) => {
        const value = normalizeModelStrategyValue(o.value)
        return value ? { value, label: o.label } : null
      })
      .filter(Boolean) as Array<{ value: StrategyValue; label: string }>
    if (genModes.length) setModelStrategyOptions(genModes)

    setEnumOptionsLoaded(true)
    // 原 finally 尾部：仅首次组装后按需初始化新建弹窗左栏
    if (!enumsInitDoneRef.current) {
      enumsInitDoneRef.current = true
      if (propsRef.current.open && !propsRef.current.flowEditMode && !propsRef.current.pageLayout) {
        initNewProjectLeftPanelRef.current()
      }
    }
  }, [dictLoaded, enumList])

  function onRightPanelModelUpdate(next: GlobalSettingData) {
    emitRef.current.onModelValueChange({
      selectedStyle: next.selectedStyle,
      myStyles: next.myStyles,
      style: next.style,
      styleSelectionTouched: next.styleSelectionTouched,
      styleLocked: next.styleLocked
    } as GlobalSettingData)
  }

  function pickProjectType(v: ProjectTypeValue) {
    if (isNewProjectModalRef.current) patchLeftPanelDraft({ projectType: v })
    emitRef.current.onProjectTypeChange(v)
  }

  function pickScriptType(v: ScriptTypeValue) {
    if (isNewProjectModalRef.current) patchLeftPanelDraft({ scriptType: v })
    emitRef.current.onScriptTypeChange(v)
    const currentMode = isNewProjectModalRef.current
      ? leftPanelDraftRef.current.creationMode
      : creationMode
    const nextMode = resolveCreationModeForScriptType(currentMode, v)
    if (nextMode !== currentMode) {
      if (isNewProjectModalRef.current) patchLeftPanelDraft({ creationMode: nextMode })
      emitRef.current.onCreationModeChange(nextMode)
    }
  }

  function pickModelStrategy(v: StrategyValue) {
    if (isNewProjectModalRef.current) patchLeftPanelDraft({ modelStrategy: v })
    emitRef.current.onModelStrategyChange(v)
  }

  function pickCreationMode(v: CreationModeValue) {
    if (isCreationModeOptionDisabled(v)) return
    if (isNewProjectModalRef.current) patchLeftPanelDraft({ creationMode: v })
    emitRef.current.onCreationModeChange(v)
    const currentScript = isNewProjectModalRef.current
      ? leftPanelDraftRef.current.scriptType
      : scriptType
    if (isScriptTypeLockedToPlot(v) && currentScript !== 'plot') {
      if (isNewProjectModalRef.current) patchLeftPanelDraft({ scriptType: 'plot' })
      emitRef.current.onScriptTypeChange('plot')
    }
  }

  function isScriptTypeOptionDisabled(value: ScriptTypeValue): boolean {
    return isScriptTypeLockedToPlot(displayCreationMode) && value === 'monologue'
  }

  function isCreationModeOptionDisabled(value: CreationModeValue): boolean {
    return isCreationModeDisabledForScriptType(value, displayScriptType)
  }


  // 原 watch([modelStrategy, modelStrategyOptions], immediate)
  useEffect(() => {
    if (flowEditMode && !contentConfigLocked) {
      ensureEnumSelection(
        modelStrategy,
        modelStrategyOptions,
        (v) => emitRef.current.onModelStrategyChange(v),
        normalizeModelStrategyValue
      )
    }
  }, [modelStrategy, modelStrategyOptions, flowEditMode, contentConfigLocked])

  // 原 watch([scriptType, scriptTypeOptions], immediate)
  useEffect(() => {
    if (flowEditMode && !contentConfigLocked) {
      ensureEnumSelection(
        scriptType,
        scriptTypeOptions,
        (v) => emitRef.current.onScriptTypeChange(v),
        normalizeScriptTypeValue
      )
    }
  }, [scriptType, scriptTypeOptions, flowEditMode, contentConfigLocked])

  // 原 watch([creationMode, creationModeOptions, displayScriptType], immediate)
  useEffect(() => {
    if (contentConfigLocked) return
    if (flowEditMode) {
      ensureEnumSelection(
        creationMode,
        creationModeOptions,
        (v) => emitRef.current.onCreationModeChange(v),
        normalizeCreationModeValue
      )
    }
    const resolved = resolveCreationModeForScriptType(creationMode, displayScriptType)
    if (resolved !== creationMode) {
      if (isNewProjectModalRef.current) patchLeftPanelDraft({ creationMode: resolved })
      emitRef.current.onCreationModeChange(resolved)
      return
    }
    if (isScriptTypeLockedToPlot(creationMode) && displayScriptType !== 'plot') {
      if (isNewProjectModalRef.current) patchLeftPanelDraft({ scriptType: 'plot' })
      emitRef.current.onScriptTypeChange('plot')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [creationMode, creationModeOptions, displayScriptType, contentConfigLocked, flowEditMode])

  // 原 watch(open, immediate)：新建弹窗每次打开重置左栏草稿并延迟挂载右侧风格库
  useEffect(() => {
    if (!open) {
      setLeftPanelReady(false)
      return
    }
    if (flowEditMode || pageLayout) {
      setLeftPanelReady(true)
      return
    }
    setLeftPanelReady(false)
    let cancelled = false
    void (async () => {
      await nextFrame()
      if (cancelled) return
      initNewProjectLeftPanelRef.current()
      await nextFrame()
      if (cancelled) return
      setLeftPanelReady(true)
    })()
    return () => {
      cancelled = true
    }
  }, [open, flowEditMode, pageLayout])

  return (
    <div
      className={`create-first-step-form${pageLayout ? ' create-first-step-form--page' : ''}${className ? ` ${className}` : ''}`}
    >
      <div className="left-panel">
        <div className="head_box">
          <img src={unionLeftIcon} alt="" />
          <h4 className="block-title">基本信息</h4>
        </div>
        <div className="content_box">
          {contentConfigLocked && (
            <p className="content-config-lock-hint" role="status">
              {contentConfigLockReason === 'assets'
                ? PROJECT_CONFIG_ASSETS_LOCKED_HINT
                : SERIES_PROJECT_CONFIG_STORYBOARD_LOCKED_HINT}
            </p>
          )}
          {hideProjectType ? null : (
          <div className="field-group inline-options" data-onboarding="create-modal-type">
            <label className="field-label">作品类型</label>
            <div className="chip-group">
              {projectTypeOptions.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  className={`chip${displayProjectType === opt.value ? ' active' : ''}${projectTypeLocked ? ' is-disabled' : ''}`}
                  aria-disabled={projectTypeLocked}
                  onClick={() => !projectTypeLocked && pickProjectType(opt.value)}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>
          )}

          <div className="field-group" data-onboarding="create-modal-title">
            <label className="field-label">作品名称</label>
            <div className="name-input-wrap">
              <input
                value={title}
                type="text"
                maxLength={25}
                className="name-input"
                placeholder="请输入作品名称"
                onChange={(e) => emitRef.current.onTitleChange(e.target.value)}
              />
              <span className="count-text">{title.length}/25</span>
            </div>
          </div>

          <div className="field-group">
            <label className="field-label">视频比例</label>
            <div className="ratio-grid">
              {ratioOptions.map((ratio) => (
                <button
                  key={ratio.value}
                  type="button"
                  className={`ratio-item${aspectRatio === ratio.value ? ' active' : ''}${contentConfigLocked ? ' is-disabled' : ''}`}
                  disabled={contentConfigLocked}
                  aria-pressed={aspectRatio === ratio.value}
                  aria-label={ratio.label}
                  onClick={() => !contentConfigLocked && emitRef.current.onAspectRatioChange(ratio.value)}
                >
                  <svg className="ratio-glyph" viewBox="0 0 24 24" aria-hidden="true"><rect x={(24-ratio.width)/2} y={(24-ratio.height)/2} width={ratio.width} height={ratio.height} rx="2" fill="currentColor" fillOpacity=".12" stroke="currentColor" strokeWidth="1.5" /></svg>
                  <span>{ratio.label}</span>
                </button>
              ))}
            </div>
          </div>

          <div className="field-group inline-options">
            <label className="field-label">剧本类型</label>
            {!enumOptionsLoaded ? (
              <p className="dict-placeholder">加载中…</p>
            ) : !scriptTypeOptions.length ? (
              <p className="dict-placeholder">暂无数据</p>
            ) : (
              <div className="chip-group">
                {scriptTypeOptions.map((opt) =>
                  contentConfigLocked || isScriptTypeOptionDisabled(opt.value) ? (
                    <Tooltip
                      key={opt.value}
                      title={
                        contentConfigLocked
                          ? SERIES_PROJECT_CONFIG_STORYBOARD_LOCKED_HINT
                          : '暂不支持'
                      }
                    >
                      <span className="chip-tooltip-wrap">
                        <button
                          type="button"
                          className={`chip is-disabled${displayScriptType === opt.value ? ' active' : ''}`}
                          disabled
                        >
                          {opt.label}
                        </button>
                      </span>
                    </Tooltip>
                  ) : (
                    <button
                      key={opt.value}
                      type="button"
                      className={`chip${displayScriptType === opt.value ? ' active' : ''}`}
                      onClick={() => pickScriptType(opt.value)}
                    >
                      {opt.label}
                    </button>
                  )
                )}
              </div>
            )}
          </div>

          <div className="field-group inline-options">
            <label className="field-label">模型策略</label>
            {!enumOptionsLoaded ? (
              <p className="dict-placeholder">加载中…</p>
            ) : !modelStrategyOptions.length ? (
              <p className="dict-placeholder">暂无数据</p>
            ) : (
              <div className="chip-group">
                {modelStrategyOptions.map((opt) => (
                  <button
                    key={opt.value}
                    type="button"
                    className={`chip${displayModelStrategy === opt.value ? ' active' : ''}${contentConfigLocked ? ' is-disabled' : ''}`}
                    disabled={contentConfigLocked}
                    onClick={() => !contentConfigLocked && pickModelStrategy(opt.value)}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="field-group inline-options">
            <label className="field-label">创作模式</label>
            {!enumOptionsLoaded ? (
              <p className="dict-placeholder">加载中…</p>
            ) : !creationModeOptions.length ? (
              <p className="dict-placeholder">暂无数据</p>
            ) : (
              <div className="chip-group">
                {creationModeOptions.map((opt) =>
                  contentConfigLocked || isCreationModeOptionDisabled(opt.value) ? (
                    <Tooltip
                      key={opt.value}
                      title={
                        contentConfigLocked
                          ? SERIES_PROJECT_CONFIG_STORYBOARD_LOCKED_HINT
                          : '解说漫暂不支持该创作模式'
                      }
                    >
                      <span className="chip-tooltip-wrap">
                        <button
                          type="button"
                          className={`chip is-disabled${displayCreationMode === opt.value ? ' active' : ''}`}
                          disabled
                        >
                          {opt.label}
                        </button>
                      </span>
                    </Tooltip>
                  ) : skipsStoryboardImageGeneration(opt.value) ? (
                    <Tooltip key={opt.value} title={PRO_MODE_NO_STORYBOARD_IMAGE_TIP}>
                      <span className="chip-tooltip-wrap">
                        <button
                          type="button"
                          className={`chip${displayCreationMode === opt.value ? ' active' : ''}`}
                          onClick={() => pickCreationMode(opt.value)}
                        >
                          {opt.label}
                        </button>
                      </span>
                    </Tooltip>
                  ) : (
                    <button
                      key={opt.value}
                      type="button"
                      className={`chip${displayCreationMode === opt.value ? ' active' : ''}`}
                      onClick={() => pickCreationMode(opt.value)}
                    >
                      {opt.label}
                    </button>
                  )
                )}
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="right-panel">
        <div className="head_box">
          <img src={unionRightIcon} alt="" />
          <h4 className="block-title">我的风格库</h4>
        </div>
        {contentReady && (
          <GlobalSetting
            modelValue={modelValue}
            styleLibraryOnly={true}
            forceStyleLocked={contentConfigLocked}
            styleLockHint={
              contentConfigLockReason === 'assets'
                ? PROJECT_CONFIG_ASSETS_STYLE_LOCK_HINT
                : '已有剧集生成分镜脚本，画面风格仅可查看。'
            }
            styleThumbSizePx={107}
            description=""
            onModelValueChange={onRightPanelModelUpdate}
          />
        )}
      </div>
    </div>
  )
}

export default CreateFirstStepFormBody
