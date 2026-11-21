'use client'

import { CloseOutlined } from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  studioCanvasShortcutCommandLabel,
  studioCanvasShortcutPlatform,
  studioCanvasShortcutSections,
  type StudioCanvasShortcutToken
} from '@/utils/studio/studioCanvasShortcuts'

function ShortcutGlyph({ token }: { token: StudioCanvasShortcutToken }) {
  if (token.kind === 'wheel') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7" /><path d="M12 8v3m0 2 2 3m-2-3-2 3" /></svg>
  }
  if (token.kind === 'pinch') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 8 4 4m0 4V4h4M16 8l4-4m0 4V4h-4M8 16l-4 4m0-4v4h4M16 16l4 4m0-4v4h-4" /></svg>
  }
  if (token.kind === 'pointer') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 4v9l-2-1a2 2 0 0 0-3 2l4 5a6 6 0 0 0 5 2h3a5 5 0 0 0 5-5V9a2 2 0 0 0-4 0" /></svg>
  }
  if (token.kind === 'trackpad') {
    return <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M12 9v4m-2 1 2 2 2-2" /></svg>
  }
  return null
}

function ShortcutChord({ tokens, command }: { tokens: StudioCanvasShortcutToken[]; command: string }) {
  return (
    <span className="studio-canvas-shortcuts__chord">
      {tokens.map((token, index) => {
        const glyph = token.kind === 'text' || token.kind === 'command' ? null : <ShortcutGlyph token={token} />
        const text = token.kind === 'command' ? command : token.kind === 'text' ? token.text : null
        return (
          <span key={`${token.kind}-${index}`} className="studio-canvas-shortcuts__piece">
            {index > 0 ? <i>+</i> : null}
            <kbd>
              {glyph}
              {text}
            </kbd>
          </span>
        )
      })}
    </span>
  )
}

export function StudioCanvasShortcutSheet({ onClose }: { onClose: () => void }) {
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const command = studioCanvasShortcutCommandLabel(studioCanvasShortcutPlatform())
  const sections = studioCanvasShortcutSections(studioMode)
  return (
    <div className="studio-canvas-shortcuts" role="dialog" aria-label="画布快捷键">
      <button type="button" className="studio-canvas-shortcuts__close" aria-label="关闭快捷键" onClick={onClose}>
        <CloseOutlined />
      </button>
      <div className="studio-canvas-shortcuts__columns">
        {sections.map((section) => (
          <section key={section.id}>
            <h3>{section.title}</h3>
            <ul>
              {section.items.map((item) => (
                <li key={item.id}>
                  <span className="studio-canvas-shortcuts__label">{item.label}</span>
                  <span className="studio-canvas-shortcuts__chords">
                    {item.chords.map((chord, index) => (
                      <ShortcutChord key={`${item.id}-${index}`} tokens={chord} command={command} />
                    ))}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  )
}
