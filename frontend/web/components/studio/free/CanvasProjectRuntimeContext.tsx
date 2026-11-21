'use client'

import { createContext, useContext } from 'react'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'

const CanvasProjectRuntimeContext = createContext<CanvasProjectWorkspaceRuntime | null>(null)

export function CanvasProjectRuntimeProvider({
  value,
  children
}: {
  value: CanvasProjectWorkspaceRuntime
  children: React.ReactNode
}) {
  return <CanvasProjectRuntimeContext.Provider value={value}>{children}</CanvasProjectRuntimeContext.Provider>
}

export function useCanvasProjectRuntime() {
  return useContext(CanvasProjectRuntimeContext)
}
