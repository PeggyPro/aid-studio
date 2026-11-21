'use client'

import { ApartmentOutlined, LoadingOutlined } from '@ant-design/icons'
import type { StudioMode } from '@/types/studio'

export function StudioModeGate({
  onChoose,
  enteringMode = null,
  error = ''
}: {
  onChoose: (mode: StudioMode) => void
  enteringMode?: StudioMode | null
  error?: string
}) {
  const entering = enteringMode !== null
  return (
    <main className="studio-mode-gate">
      <div className="studio-mode-gate__panel">
        <span className="studio-mode-gate__eyebrow">AID Studio · 创作工作台</span>
        <h1>进入自由画布</h1>
        <p>从任意节点开始，独立组织素材与创作内容。</p>
        {error ? <p className="studio-mode-gate__error" role="alert">{error}</p> : null}
        <div className="studio-mode-gate__choices" style={{ gridTemplateColumns:'1fr' }}>
          <button type="button" disabled={entering} onClick={() => onChoose('free')}>
            {enteringMode === 'free' ? <LoadingOutlined spin /> : <ApartmentOutlined />}
            <strong>自由画布</strong>
            <span>{enteringMode === 'free' ? '正在进入…' : '从任意节点开始，不向步骤流程写入数据。'}</span>
          </button>
        </div>
      </div>
    </main>
  )
}
