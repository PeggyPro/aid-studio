'use client'

import { createContext } from 'react'

/** A menu closes its nearest popup, including uncontrolled and nested popovers. */
export const StudioCanvasPopoverCloseContext = createContext<(() => void) | null>(null)
