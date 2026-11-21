'use client'

import type { ReactNode } from 'react'
import './studio-canvas-collapse.css'

/** Retain content through both directions of the transition; closed controls are inert. */
export function StudioCanvasCollapse({ open, children, id }: { open: boolean; children: ReactNode; id?: string }) {
  return <div id={id} className={`studio-canvas-collapse${open ? ' is-open' : ''}`} aria-hidden={!open} inert={!open}>
    <div>{children}</div>
  </div>
}
