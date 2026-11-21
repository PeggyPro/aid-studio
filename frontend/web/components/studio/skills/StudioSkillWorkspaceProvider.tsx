'use client'

import {
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode
} from 'react'
import { useStudioUiStore } from '@/stores/studioUi'
import { useUserStore } from '@/stores/user'
import { StudioPersonalSkillManager } from './StudioPersonalSkillManager'
import { useCanvasAssistantIdentity } from '@/hooks/studio/useCanvasAssistantIdentity'

export type SkillAuthoringRequest = { id: number; material?: string }
type Workspace = {
  revision: number
  changed: () => void
  openManager: () => void
  beginAuthoring: (material?: string) => void
  request: SkillAuthoringRequest | null
  consume: (id: number) => void
}
const SkillWorkspace = createContext<Workspace | null>(null)
export const useStudioSkillWorkspace = () => useContext(SkillWorkspace)

/** One maintenance host per canvas/account, shared by the avatar and conversation entries. */
export function StudioSkillWorkspaceProvider({ children }: { children: ReactNode }) {
  const scope = useStudioUiStore((state) => state.scopeKey)
  const userId = useUserStore((state) => state.user?.id)
  const identity = useCanvasAssistantIdentity()
  return (
    <SkillWorkspaceHost binding={JSON.stringify([scope, userId, identity.identity])}>
      {children}
    </SkillWorkspaceHost>
  )
}

function SkillWorkspaceHost({ children, binding }: { children: ReactNode; binding: string }) {
  const alive = useRef(true)
  const activeBinding = useRef(binding)
  const active = () => alive.current && activeBinding.current === binding
  const sequence = useRef(0)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])
  const [manager, setManager] = useState({ binding, open: false })
  const [revision, setRevision] = useState(0)
  const [pending, setPending] = useState<{
    binding: string
    request: SkillAuthoringRequest | null
  }>({ binding, request: null })
  useLayoutEffect(() => {
    activeBinding.current = binding
    setManager((current) => (current.binding === binding ? current : { binding, open: false }))
    setPending((current) => (current.binding === binding ? current : { binding, request: null }))
  }, [binding])
  const changed = () => {
    if (active()) setRevision((value) => value + 1)
  }
  const beginAuthoring = (material?: string) => {
    if (!active()) return
    setManager({ binding, open: false })
    setPending({ binding, request: { id: ++sequence.current, material } })
    useStudioUiStore.getState().setRightCollapsed(false)
  }
  return (
    <SkillWorkspace.Provider
      value={{
        revision,
        changed,
        openManager: () => {
          if (active()) setManager({ binding, open: true })
        },
        beginAuthoring,
        request: pending.binding === binding ? pending.request : null,
        consume: (id) => {
          if (active())
            setPending((current) =>
              current.binding === binding && current.request?.id === id
                ? { binding, request: null }
                : current
            )
        }
      }}
    >
      {children}
      <StudioPersonalSkillManager
        key={binding}
        open={manager.binding === binding && manager.open}
        onClose={() => {
          if (active()) setManager({ binding, open: false })
        }}
        onChanged={changed}
      />
    </SkillWorkspace.Provider>
  )
}
