'use client'

import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Input, Modal, message } from 'antd'
import { SearchOutlined, VideoCameraOutlined } from '@ant-design/icons'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'
import { usePromptDictionary } from '@/hooks/usePromptDictionary'
import type { SettingSelectOption } from '@/hooks/promptDictionaryVocabulary'
import type { StudioCameraMarkKind } from '@/utils/studio/studioCameraMark'
import { StudioComposerChip } from './StudioComposerChip'
import { StudioDictionaryPreview } from './StudioDictionaryPreview'
import { useStudioCameraLibrary } from './studioCameraLibraryContext'
import { useStudioComposerPresentation } from './studioComposerPresentationContext'
import { StudioSegmentedTabs } from './StudioSegmentedTabs'

const cameraTabs: readonly { value: StudioCameraMarkKind; label: string }[] = [
  { value: 'technique', label: '拍摄手法' },
  { value: 'motion', label: '运镜广场' },
  { value: 'shot_size', label: '景别' },
  { value: 'camera_angle', label: '拍摄角度' }
]

function CameraOptionPreview({ item }: { item: SettingSelectOption }) {
  const rawUrl = String(item.image ?? '').trim()
  return <StudioDictionaryPreview key={rawUrl} url={rawUrl} label={`${item.value}预览`}
    fallback={<i><StudioChromeIcon name="cameraShot" size="1em" fallback={<VideoCameraOutlined />} /><small>暂无预览</small></i>} />
}

export function StudioCameraMovePanel({ imageOnly = false }: { imageOnly?: boolean }) {
  const defaultTab = imageOnly ? 'shot_size' : 'motion'
  const panelId = useId()
  const presentation = useStudioComposerPresentation()
  const library = useStudioCameraLibrary()
  const [open, setOpen] = useState(false)
  const [tab, setTab] = useState<StudioCameraMarkKind>(defaultTab)
  const [query, setQuery] = useState('')
  const [replaceId, setReplaceId] = useState<string | null>(null)
  const [activeKey, setActiveKey] = useState<string | null>(null)
  const seenRequest = useRef(0)
  const { ensureLoaded, cameraMovementOptions, shootingTechniqueOptions, shotSizeOptions, cameraAngleOptions } = usePromptDictionary()

  useEffect(() => { void ensureLoaded() }, [ensureLoaded])

  useEffect(() => {
    const request = library?.request
    if (!request || request.seq === seenRequest.current) return
    seenRequest.current = request.seq
    setTab(request.tab)
    setReplaceId(request.replaceId ?? null)
    setActiveKey(request.optionKey ?? null)
    setQuery('')
    setOpen(true)
  }, [library?.request])

  const options = useMemo(() => {
    const source = {
      motion: cameraMovementOptions,
      technique: shootingTechniqueOptions,
      shot_size: shotSizeOptions,
      camera_angle: cameraAngleOptions
    }[tab] ?? []
    const keyword = query.trim()
    return source.filter((item) => item.key !== 'none' && (!keyword || item.value.includes(keyword)))
  }, [cameraAngleOptions, cameraMovementOptions, query, shootingTechniqueOptions, shotSizeOptions, tab])

  const chooseTab = (next: StudioCameraMarkKind) => {
    setTab(next)
    setQuery('')
  }

  const pick = (item: SettingSelectOption) => {
    if (!item.description?.trim()) {
      message.warning('该词条尚未配置中文提示词，请在后台补齐后使用')
      return
    }
    const mark = { kind: tab, optionKey: item.key, label: item.value, ...(item.description ? { description: item.description } : {}) }
    if (replaceId) library?.replaceMark(replaceId, mark)
    else library?.insertMark(mark)
    setOpen(false)
  }

  return (
    <>
      <StudioComposerChip
        icon={<StudioChromeIcon name={imageOnly ? 'cameraShot' : 'cameraMove'} fallback={<VideoCameraOutlined />} />}
        label={imageOnly ? "镜头" : "运镜"}
        active={open}
        onClick={() => {
          setTab(defaultTab)
          setReplaceId(null)
          setActiveKey(null)
          setQuery('')
          setOpen(true)
        }}
      />
      <Modal
        getContainer={presentation?.centered ? () => presentation.getOverlayContainer() ?? document.body : undefined}
        open={open}
        title={(
          <div className="studio-camera-library__heading">
            <StudioSegmentedTabs options={imageOnly ? cameraTabs.slice(2) : cameraTabs}
              value={tab} onChange={chooseTab} ariaLabel="镜头分类" panelId={panelId} />
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="搜索词条名称"
              aria-label="搜索镜头词条"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </div>
        )}
        footer={null}
        width="min(840px, calc(100vw - 32px))"
        centered
        destroyOnHidden
        className="create-flow-modal studio-camera-library"
        onCancel={() => setOpen(false)}
      >
        <div className="studio-camera-library__grid" id={panelId} role="tabpanel" aria-labelledby={`${panelId}-tab-${tab}`}>
          {options.length ? options.map((item) => (
            <button
              key={item.key}
              type="button"
              className={activeKey === item.key ? 'is-active' : ''}
              aria-pressed={activeKey === item.key}
              onClick={() => pick(item)}
            >
              <span className="studio-camera-library__media"><CameraOptionPreview item={item} /></span>
              <span>{item.value}</span>
            </button>
          )) : <p>{query.trim() ? '没有匹配的结果' : '暂无数据'}</p>}
        </div>
      </Modal>
    </>
  )
}
