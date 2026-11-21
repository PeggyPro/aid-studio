'use client'

import { useEffect, useState } from 'react'
import { CANVAS_AGENT_TYPEWRITER_PROMPTS } from '@/utils/studio/canvasAgentTypewriter'

const TYPE_MS = 72
const HOLD_MS = 1600
const ERASE_MS = 28
const GAP_MS = 420

/** 逐字打出示例，打完停顿后擦掉，再换下一条。active 变 false 时立刻清空。 */
export function useCanvasAgentTypewriter(active: boolean, prompts: readonly string[] = CANVAS_AGENT_TYPEWRITER_PROMPTS) {
  const [text, setText] = useState('')

  useEffect(() => {
    if (!active || !prompts.length) return
    let alive = true
    let prompt = 0
    let index = 0
    let mode: 'type' | 'hold' | 'erase' | 'gap' = 'type'
    let timer = 0
    const tick = () => {
      if (!alive) return
      const current = prompts[prompt]
      if (mode === 'type') {
        index += 1
        setText(current.slice(0, index))
        if (index >= current.length) mode = 'hold'
        timer = window.setTimeout(tick, mode === 'hold' ? HOLD_MS : TYPE_MS)
        return
      }
      if (mode === 'hold') {
        mode = 'erase'
        timer = window.setTimeout(tick, ERASE_MS)
        return
      }
      if (mode === 'erase') {
        index -= 1
        setText(current.slice(0, Math.max(0, index)))
        if (index <= 0) {
          prompt = (prompt + 1) % prompts.length
          mode = 'gap'
          timer = window.setTimeout(tick, GAP_MS)
        } else {
          timer = window.setTimeout(tick, ERASE_MS)
        }
        return
      }
      mode = 'type'
      timer = window.setTimeout(tick, TYPE_MS)
    }
    timer = window.setTimeout(tick, TYPE_MS)
    return () => {
      alive = false
      window.clearTimeout(timer)
      setText('')
    }
  }, [active, prompts])

  return text
}
