'use client'

import { StudioSegmentedTabs } from './StudioSegmentedTabs'

/** 模型目录共用等宽分段页签；键盘选择与点击使用同一回调。 */
export function StudioModelTypeTabs<T extends string>({ options, value, onChange }: {
  options: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return <StudioSegmentedTabs options={options} value={value} onChange={onChange}
    ariaLabel="模型类型" className="studio-model-menu__tabs" />
}
