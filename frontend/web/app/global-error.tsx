'use client'

import '@/assets/font/typography.css'

import { useState } from 'react'

export default function GlobalError() {
  const [reloading, setReloading] = useState(false)

  const reload = () => {
    setReloading(true)
    window.location.reload()
  }

  return (
    <html lang="zh-CN">
      <body style={{ margin: 0, background: 'var(--v10-bg, #0f1012)', color: 'var(--v10-text, #f1f2f4)' }}>
        <main style={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', padding: 24, boxSizing: 'border-box' }}>
          <section aria-labelledby="page-error-heading" style={{ width: '100%', maxWidth: 420, padding: 32, boxSizing: 'border-box', border: '1px solid var(--v10-hair, rgba(255,255,255,.06))', borderRadius: 16, background: 'var(--v10-s1, #16171a)' }}>
            <h1 id="page-error-heading" style={{ margin: '0 0 12px', fontSize: 24, lineHeight: 1.4 }}>页面暂时无法加载</h1>
            <p style={{ margin: '0 0 24px', color: 'var(--v10-muted, #a8abb3)', lineHeight: 1.7 }}>请重新加载当前页面，再查看最新状态。</p>
            <button
              type="button"
              disabled={reloading}
              onClick={reload}
              style={{ minHeight: 44, padding: '10px 20px', border: 0, borderRadius: 8, background: 'var(--v10-primary, #f1f2f4)', color: 'var(--v10-on-primary, #0f1012)', font: 'inherit', cursor: reloading ? 'wait' : 'pointer', opacity: reloading ? 0.7 : 1 }}
            >
              {reloading ? '正在重新加载…' : '重新加载'}
            </button>
          </section>
        </main>
      </body>
    </html>
  )
}
