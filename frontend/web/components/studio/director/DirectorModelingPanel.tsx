'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { App } from 'antd'
import { ArrowRightOutlined, FolderOpenOutlined } from '@ant-design/icons'
import { PrevizProjectDialog } from '@/components/previz/PrevizProjectDialog'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'
import { PrevizAuthoring, type PrevizAuthoringTools } from '@/components/previz/PrevizEditor'
import { PrevizServerProjects } from '@/components/previz/PrevizServerProjects'
import { PrevizModalProvider } from '@/components/previz/PrevizModalProvider'
import { createPrevizWorkingCopyStore } from '@/utils/previz/gateway'
import { readServerProject } from '@/utils/previz/createServerProject'
import { mainAssistantTransport } from '@/utils/business/assistant'
import { useStudioUiStore } from '@/stores/studioUi'
import type { PrevizProject } from '@/types/previz'
import type { ComponentProps } from 'react'
import '@/components/previz/previz.css'
import '@/components/previz/previz-workspace.css'

export function DirectorModelingTools({ onImport, children, ...authoring }: Omit<ComponentProps<typeof PrevizAuthoring>, 'initial' | 'onOpenProjects' | 'children'> & {
  onImport: (project: PrevizProject) => void
  children: (tools: PrevizAuthoringTools) => React.ReactNode
}) {
  const { message } = App.useApp()
  const { owner, session } = authoring
  const gateway = useMemo(() => createPrevizWorkingCopyStore(owner), [owner])
  const [projectsOpen, setProjectsOpen] = useState(false)
  const [opening, setOpening] = useState(false)
  const [workingCopies, setWorkingCopies] = useState<PrevizProject[]>([])
  const flight = useRef(false)
  const scopeKey = useStudioUiStore(state => state.scopeKey)
  useEffect(() => {
    if (!projectsOpen) return
    let current = true
    void gateway.list().then(items => { if (current) setWorkingCopies(items) }).catch(error => { if (current) message.error(error instanceof Error ? error.message : '本机旧工程读取失败') })
    return () => { current = false }
  }, [gateway, projectsOpen, message])
  const open = async (id: string) => {
    if (flight.current) return
    flight.current = true
    setOpening(true)
    const identity = mainAssistantTransport.identityKey()
    const guard = () => {
      if (identity !== mainAssistantTransport.identityKey() || useStudioUiStore.getState().scopeKey !== scopeKey) throw new Error('账号或画布已切换，未载入旧工程')
    }
    try {
      await session.flush()
      guard()
      const local = await gateway.get(id)
      guard()
      const project = local?.remoteWorkspaceId ? await readServerProject(local.remoteWorkspaceId, guard) : local ?? await readServerProject(id, guard)
      guard()
      onImport(project)
      setProjectsOpen(false)
    } catch (error) { message.error(error instanceof Error ? error.message : '旧工程载入失败') }
    finally { flight.current = false; setOpening(false) }
  }
  const localProjects = workingCopies.filter(project => !project.remoteWorkspaceId)
  return <PrevizModalProvider>
    <PrevizAuthoring key={session.project.id} initial={session.project} {...authoring} onOpenProjects={() => setProjectsOpen(true)}>{children}</PrevizAuthoring>
    <PrevizProjectDialog title="迁入旧建模工程" open={projectsOpen} onCancel={() => { if (!opening) setProjectsOpen(false) }}>
      <div className="pv-project-import">
        <p className="pv-project-import__note">迁入会替换当前建模内容，保留人物表演、机位、动作和手机轨迹。可使用撤销恢复。</p>
        {localProjects.length > 0 && (
          <section className="pv-local-projects" aria-label="本机旧工程">
            <h2>本机旧工程</h2>
            <div className="pv-local-projects__list">
              {localProjects.map(project => (
                <StudioCanvasTooltip key={project.id} title={project.title} trigger={['hover', 'focus']}>
                  <button type="button" disabled={opening} onClick={() => void open(project.id)}>
                    <FolderOpenOutlined aria-hidden />
                    <span className="pv-local-projects__title">{project.title}</span>
                    <ArrowRightOutlined aria-hidden />
                  </button>
                </StudioCanvasTooltip>
              ))}
            </div>
          </section>
        )}
        <PrevizServerProjects embedded workingCopies={workingCopies} onOpen={async project => { await session.flush(); onImport(project); setProjectsOpen(false) }} />
      </div>
    </PrevizProjectDialog>
  </PrevizModalProvider>
}
