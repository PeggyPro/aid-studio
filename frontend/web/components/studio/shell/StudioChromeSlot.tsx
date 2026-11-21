'use client'

import { createContext, useContext, useState, type ReactNode, type Dispatch, type SetStateAction } from 'react'

const ChromeSlotContext = createContext<{
  target: HTMLDivElement | null
  setTarget: Dispatch<SetStateAction<HTMLDivElement | null>>
}>({ target: null, setTarget: () => {} })

export function StudioChromeProvider({ children }: { children: ReactNode }) {
  const [target, setTarget] = useState<HTMLDivElement | null>(null)
  return <ChromeSlotContext.Provider value={{ target, setTarget }}>{children}</ChromeSlotContext.Provider>
}

export function StudioChromeSlot() {
  const { setTarget } = useContext(ChromeSlotContext)
  return <div className="studio-topbar__tools" ref={setTarget} />
}

export function useStudioChromeTarget() {
  return useContext(ChromeSlotContext).target
}
