'use client'

import { MoonOutlined, SunOutlined } from '@ant-design/icons'
import { Button, Tooltip } from 'antd'
import { useStudioFreeTheme } from './StudioFreeThemeProvider'

export function StudioFreeThemeSwitch({ className = '' }: { className?: string }) {
  const { theme, isAutomatic, siteSynced, toggleTheme } = useStudioFreeTheme()
  const currentName = theme === 'opal' ? '月白' : '柔雾'
  const modeName = isAutomatic ? `自动（当前${currentName}）` : currentName
  const nextName = siteSynced ? theme === 'opal' ? '柔雾' : '月白'
    : isAutomatic ? '月白' : theme === 'opal' ? '柔雾' : '自动'

  return (
    <Tooltip title={`当前${modeName}，点击切换${nextName}`}>
      <Button
        type="text"
        shape="circle"
        className={`studio-free-theme-switch${className ? ` ${className}` : ''}`}
        icon={(
          <span className="studio-free-theme-switch__icon" aria-hidden>
            {theme === 'opal' ? <SunOutlined /> : <MoonOutlined />}
            {isAutomatic ? <small>A</small> : null}
          </span>
        )}
        aria-label={`主题模式：${modeName}；切换到${nextName}`}
        onClick={toggleTheme}
      />
    </Tooltip>
  )
}
