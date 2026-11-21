import type { ReactNode } from 'react'
import { DeleteOutlined } from '@ant-design/icons'
import type { AssistantSessionPickerProps } from '@/components/assistant/AssistantSessionPanel'
import type { StudioCanvasDropdownItem } from './StudioCanvasDropdownMenu'

export const ASSISTANT_HISTORY_MORE_KEY = '__assistant_more_sessions__'

/** Shared history rendering for conversation and node shells; paging never changes the selected session. */
export function studioAssistantHistoryItems(picker: AssistantSessionPickerProps, icon: ReactNode,
  additionalItems: StudioCanvasDropdownItem[] = []): StudioCanvasDropdownItem[] {
  const items: StudioCanvasDropdownItem[] = [
    ...picker.options.map(item => ({ key: item.value, label: item.label, icon, disabled: picker.disabled,
      action: picker.onDelete ? { label: `删除对话：${item.label}`, icon: <DeleteOutlined />, disabled: picker.disabled || picker.deleteDisabled,
        onClick: () => picker.onDelete?.(item.value) } : undefined })),
    ...additionalItems,
  ]
  if (picker.hasMore && picker.onLoadMore) items.push({
    key: ASSISTANT_HISTORY_MORE_KEY,
    label: picker.loading ? '正在加载…' : picker.error ? '重试加载更早的对话' : '加载更早的对话',
    description: picker.error ? '历史对话加载失败，请重试。' : undefined,
    icon,
    disabled: picker.disabled,
    keepOpenOnSelect: true,
  })
  return items
}
