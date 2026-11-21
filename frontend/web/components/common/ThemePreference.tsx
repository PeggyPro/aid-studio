'use client'

import { MoonOutlined, SunOutlined } from '@ant-design/icons'
import { usePathname } from 'next/navigation'
import { V10_THEME_OPTIONS } from '~/config/v10Theme'
import { supportsV10Theme } from '~/utils/v10Surface'
import { setV10Theme, useV10Theme, type V10ThemeId } from '~/utils/v10ThemePreference'
import './ThemePreference.css'

const THEME_MARK = (
  <svg className="theme-preference__mark" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
    <circle cx="8" cy="8" r="6.25" fill="none" stroke="currentColor" strokeWidth="1.4" />
    <path d="M8 1.75a6.25 6.25 0 0 1 0 12.5Z" fill="currentColor" />
  </svg>
)

function themeIcon(id: V10ThemeId) {
  return id === 'paper' ? <SunOutlined aria-hidden /> : <MoonOutlined aria-hidden />
}

/** 用户菜单里的外观行：和「我的投稿」同一行高，右侧用日/月图标切换。 */
export function ThemePreference() {
  const current = useV10Theme()
  const pathname = usePathname() ?? '/'
  if (!supportsV10Theme(pathname)) return null
  const options = (['paper', 'graphite'] as const).flatMap((id) => {
    const option = V10_THEME_OPTIONS.find((item) => item.id === id)
    return option ? [option] : []
  })
  return (
    <div className="user-menu-dropdown__item theme-preference" role="group" aria-label="外观主题">
      {THEME_MARK}
      <span className="theme-preference__label">外观</span>
      <div className="theme-preference__switch" role="radiogroup" aria-label="外观主题">
        {options.map(({ id, label }) => (
          <button
            key={id}
            type="button"
            role="radio"
            aria-label={label}
            aria-checked={current === id}
            className="theme-preference__option"
            onClick={(event) => {
              event.stopPropagation()
              setV10Theme(id)
            }}
          >
            {themeIcon(id)}
          </button>
        ))}
      </div>
    </div>
  )
}
