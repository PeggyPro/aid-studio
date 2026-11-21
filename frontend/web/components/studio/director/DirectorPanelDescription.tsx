import type { ReactNode } from 'react'

/** 工具名称由侧栏容器统一渲染；内容区只提供当前工具说明。 */
export function DirectorPanelDescription({ children }: { children: ReactNode }) {
  return <p className="director-panel-description">{children}</p>
}
