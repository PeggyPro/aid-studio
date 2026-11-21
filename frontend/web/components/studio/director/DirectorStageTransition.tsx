'use client'

import { Component, type ErrorInfo, type ReactNode } from 'react'
import { App, Button } from 'antd'
import { CloseOutlined, ReloadOutlined } from '@ant-design/icons'
import { StudioFreeThemeScope } from '../theme/StudioFreeThemeProvider'

export function DirectorStageLoading() {
  return (
    <div className="director-stage-loading" role="status" aria-live="polite" aria-label="正在打开3D预演">
      <div className="director-stage-loading__panel">
        <div className="director-stage-loading__mark" aria-hidden="true"><span>AI·D</span><i /></div>
        <strong>正在打开3D预演</strong>
        <p>准备场景、角色与机位控制器</p>
        <div className="director-stage-loading__track" aria-hidden="true"><span /></div>
        <div className="director-stage-loading__steps" aria-hidden="true">
          <span className="is-active">场景</span><span>模型</span><span>机位</span>
        </div>
      </div>
    </div>
  )
}

export function DirectorStageRuntimeError({ reason, onRetry, onClose, title = '三维视口初始化失败', retryLabel = '重试初始化' }: {
  reason: string; onRetry: () => void; onClose: () => void; title?: string; retryLabel?: string
}) {
  return (
    <div className="director-stage-loading director-stage-loading--error" role="alert">
      <div className="director-stage-loading__panel">
        <div className="director-stage-loading__mark is-error" aria-hidden="true"><span>AI·D</span><i /></div>
        <strong>{title}</strong>
        <p>{reason}</p>
        <div className="director-stage-loading__actions">
          <Button icon={<CloseOutlined />} onClick={onClose}>返回画布</Button>
          <Button type="primary" icon={<ReloadOutlined />} onClick={onRetry}>{retryLabel}</Button>
        </div>
      </div>
    </div>
  )
}

interface DirectorStageBoundaryProps {
  children: ReactNode
  onClose: () => void
}

interface DirectorStageBoundaryState {
  failed: boolean
}

export function DirectorStageBoundary(props: DirectorStageBoundaryProps) {
  return (
    <StudioFreeThemeScope themeOverride="soft-mist" className="director-theme-scope">
      <App component={false}>
        <DirectorStageErrorBoundary {...props} />
      </App>
    </StudioFreeThemeScope>
  )
}

class DirectorStageErrorBoundary extends Component<DirectorStageBoundaryProps, DirectorStageBoundaryState> {
  state: DirectorStageBoundaryState = { failed: false }

  static getDerivedStateFromError(): DirectorStageBoundaryState {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[Studio Director] 导演台加载失败', error, info.componentStack)
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div className="director-stage-loading director-stage-loading--error" role="alert">
        <div className="director-stage-loading__panel">
          <div className="director-stage-loading__mark is-error" aria-hidden="true"><span>AI·D</span><i /></div>
          <strong>3D 导演台加载失败</strong>
          <p>当前画布内容未受影响，可退出后重试或重新加载页面。</p>
          <div className="director-stage-loading__actions">
            <Button icon={<CloseOutlined />} onClick={this.props.onClose}>返回画布</Button>
            <Button type="primary" icon={<ReloadOutlined />} onClick={() => window.location.reload()}>重新加载</Button>
          </div>
        </div>
      </div>
    )
  }
}
