'use client'

import { useMemo, useState } from 'react'
import { Button, Collapse, Input, Modal, Tooltip, message } from 'antd'
import {
  ApartmentOutlined,
  AppstoreOutlined,
  ClockCircleOutlined,
  DeleteOutlined,
  DoubleLeftOutlined,
  ExportOutlined,
  SaveOutlined,
  SearchOutlined,
  ShareAltOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import { STUDIO_NODE_META, STUDIO_SKILLS } from '@/config/studioMeta'
import { useStudioCommands } from '@/hooks/studio/useStudioCommands'
import { useStudioUiStore } from '@/stores/studioUi'
import type { StudioSkill } from '@/types/studio'
import { StudioNodeIcon } from '../canvas/StudioNodeIcon'
import { StudioCanvasTooltip } from '../canvas/StudioCanvasTooltip'
import { StudioStatusBadge } from '../canvas/StudioStatusBadge'
import { STUDIO_PANE_ADD_MENU } from '@/utils/studio/studioPaneMenu'
import { selectStudioFlowStepNode } from '@/utils/studio/studioFlowNodeSelection'
import { useStudioFlowRuntime } from '../flow/StudioFlowRuntimeProvider'
import type { StudioFlowStep } from '@/types/studio'
import { StudioTaskList } from './StudioTaskList'
import { filterStudioFlowCanvasVisibleNodes } from '@/utils/studio/studioFlowCanvasProjection'
import { StudioFlowDeliveryPanel } from '../flow/agent/StudioFlowDeliveryPanel'
import { useStudioPanelPresence, useStudioExitingPanelRef } from '@/hooks/studio/useStudioPanelPresence'

export function StudioLeftPanel({ readOnly = false }: { readOnly?: boolean }) {
  const collapsed = useStudioUiStore((state) => state.leftCollapsed)
  const setCollapsed = useStudioUiStore((state) => state.setLeftCollapsed)
  const studioMode = useStudioUiStore((state) => state.studioMode)
  const { rendered, exiting } = useStudioPanelPresence(!collapsed)

  return rendered ? (
    studioMode === 'flow'
      ? <StudioFlowLeftPanel exiting={exiting} onCollapse={() => setCollapsed(true)} />
      : <StudioFreeLeftPanel readOnly={readOnly} exiting={exiting} onCollapse={() => setCollapsed(true)} />
  ) : null
}

const FLOW_STEPS: Array<{ step: StudioFlowStep; label: string; description: string }> = [
  { step: 'global-setting', label: '项目配置', description: '风格、创作模型、比例与策略' },
  { step: 'story-script', label: '剧本创作', description: '富文本、导入、历史与 Agent' },
  { step: 'scene-character', label: '素材准备', description: '场景、角色、道具与形态' },
  { step: 'storyboard-script', label: '分镜设计', description: '拆镜、脚本与分镜图' },
  { step: 'storyboard-video', label: '视频生成', description: '原流程创作模式与视频任务' },
  { step: 'dubbing', label: '音画同步', description: '台词、音色、配音与对口型' },
  { step: 'preview', label: '成品预览', description: '时间轴、导出与发布' }
]

function StudioFreeLeftPanel({ onCollapse, exiting, readOnly }: { onCollapse: () => void; exiting: boolean; readOnly: boolean }) {
  const panelRef = useStudioExitingPanelRef(exiting)
  return (
    <aside
      ref={panelRef}
      className={`studio-left-panel${exiting ? ' is-exiting' : ''}`}
      data-studio-guide="planning"
      aria-hidden={exiting}
      inert={exiting || readOnly}
      aria-label="画布工具"
    >
      <div className="studio-left-panel__content">
        <div className="studio-panel-scroll studio-start-panel">
          <AddNodePanel onCollapse={onCollapse} exiting={exiting} />
          <Collapse
            ghost
            size="small"
            className="studio-left-collapse"
            items={[
              { key: 'tasks', label: <span><ClockCircleOutlined /> 任务</span>, children: <StudioTaskList limit={14} /> },
              { key: 'skills', label: <span><ThunderboltOutlined /> Skill</span>, children: <SkillPanel /> },
              { key: 'nodes', label: <span><ApartmentOutlined /> 节点</span>, children: <NodeListPanel /> }
            ]}
          />
        </div>
      </div>
    </aside>
  )
}

function StudioFlowLeftPanel({ onCollapse, exiting }: { onCollapse: () => void; exiting: boolean }) {
  const runtime = useStudioFlowRuntime()
  const nodes = useStudioUiStore((state) => state.nodes)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
  const activeNodeId = useStudioUiStore((state) => state.activeNodeId)
  const visibleItems = useMemo(
    () => filterStudioFlowCanvasVisibleNodes(nodes).filter((node) => node.data.flowBinding?.role === 'item'),
    [nodes]
  )
  const activeStep = useMemo(() => {
    const id = activeNodeId ?? selectedNodeIds[0]
    return nodes.find((node) => node.id === id)?.data.flowBinding?.step
  }, [activeNodeId, nodes, selectedNodeIds])
  const panelRef = useStudioExitingPanelRef(exiting)

  const openFlowStep = (step: StudioFlowStep) => {
    if (step === 'global-setting') {
      runtime?.openEditor('global-setting')
      return
    }
    if (!selectStudioFlowStepNode(step)) {
      message.info('该步骤尚无内容节点，请先在右侧 Agent 中生成')
    }
  }
  return (
    <aside
      ref={panelRef}
      className={`studio-left-panel${exiting ? ' is-exiting' : ''}`}
      data-studio-guide="planning"
      aria-hidden={exiting}
      inert={exiting}
    >
      <div className="studio-panel-heading">
        <div><strong>流程导航</strong><span>步骤 · 实体 · 任务 · 交付</span></div>
        <Tooltip title="收起流程面板">
          <Button size="small" type="text" icon={<DoubleLeftOutlined />} aria-label="收起流程面板" aria-expanded={!exiting} onClick={onCollapse} />
        </Tooltip>
      </div>
      <div className="studio-left-panel__content">
        <div className="studio-panel-scroll studio-flow-step-panel">
          <p className="studio-panel-help studio-panel-help--lead">右侧 Agent 负责创作，左侧用来跳转步骤和已生成节点。</p>
          <Collapse
            ghost
            size="small"
            className="studio-left-collapse"
            defaultActiveKey={['steps', 'entities', 'tasks', 'delivery']}
            items={[
              {
                key: 'steps',
                label: <span><ApartmentOutlined /> 创作步骤</span>,
                children: (
                  <div className="studio-flow-step-list">
                    {FLOW_STEPS.map((item, index) => (
                      <button
                        key={item.step}
                        type="button"
                        className={activeStep === item.step ? 'is-active' : ''}
                        onClick={() => openFlowStep(item.step)}
                      >
                        <i>{String(index + 1).padStart(2, '0')}</i>
                        <span><strong>{item.label}</strong><small>{item.description}</small></span>
                      </button>
                    ))}
                  </div>
                )
              },
              {
                key: 'entities',
                label: <span><AppstoreOutlined /> 画布实体 · {visibleItems.length}</span>,
                children: !visibleItems.length ? (
                  <p className="studio-panel-help">暂无可见节点。在右侧生成并带入剧本后，素材与分镜会自动出现在此。</p>
                ) : (
                  <div className="studio-node-list">
                    {visibleItems.slice(0, 120).map((node) => (
                      <button key={node.id} type="button" onClick={() => selectNodes([node.id])}>
                        <span className="studio-node-list__icon"><StudioNodeIcon kind={node.data.kind} /></span>
                        <span>
                          <strong>{node.data.title}</strong>
                          <small>{FLOW_STEPS.find((item) => item.step === node.data.flowBinding?.step)?.label ?? node.data.flowBinding?.step}</small>
                        </span>
                        <StudioStatusBadge status={node.data.status} />
                      </button>
                    ))}
                  </div>
                )
              },
              {
                key: 'tasks',
                label: <span><ThunderboltOutlined /> 生成任务</span>,
                children: (
                  <>
                    <p className="studio-panel-help">查看提取、出图、分镜等后台任务进度；与顶栏「进行中」统计同源。</p>
                    <StudioTaskList limit={14} />
                  </>
                )
              },
              {
                key: 'delivery',
                label: <span><ExportOutlined /> 交付</span>,
                children: <StudioFlowDeliveryPanel />
              }
            ]}
          />
        </div>
      </div>
    </aside>
  )
}

function AddNodePanel({ onCollapse, exiting }: { onCollapse: () => void; exiting: boolean }) {
  const addUserNode = useStudioUiStore((state) => state.addUserNode)

  return (
    <section className="studio-panel-section studio-start-grid">
      <header className="studio-start-grid__heading">
        <h3>添加节点</h3>
        <StudioCanvasTooltip title="收起画布工具">
          <Button size="small" type="text" icon={<DoubleLeftOutlined />} aria-label="收起画布工具" aria-expanded={!exiting} onClick={onCollapse} />
        </StudioCanvasTooltip>
      </header>
      <div>
        {STUDIO_PANE_ADD_MENU.map(({ kind, label }) => (
          <button key={kind} type="button" onClick={() => addUserNode(kind)}>
            <StudioNodeIcon kind={kind} /><span>{label}</span>
          </button>
        ))}
      </div>
    </section>
  )
}

function SkillPanel() {
  const [query, setQuery] = useState('')
  const [saveOpen, setSaveOpen] = useState(false)
  const [name, setName] = useState('我的镜头生产 Skill')
  const personalSkills = useStudioUiStore((state) => state.personalSkills)
  const savePersonalSkill = useStudioUiStore((state) => state.savePersonalSkill)
  const deletePersonalSkill = useStudioUiStore((state) => state.deletePersonalSkill)
  const { runSkill } = useStudioCommands()
  const items = [...STUDIO_SKILLS, ...personalSkills].filter((item) =>
    `${item.name}${item.description}`.toLowerCase().includes(query.trim().toLowerCase())
  )

  return (
    <div className="studio-embedded-panel">
      <Input allowClear prefix={<SearchOutlined />} placeholder="搜索 Skill" value={query} onChange={(event) => setQuery(event.target.value)} />
      <div className="studio-library-actions">
        <Button icon={<SaveOutlined />} onClick={() => setSaveOpen(true)}>保存个人 Skill</Button>
        <Button icon={<AppstoreOutlined />} onClick={() => message.info('Skill 广场使用 Mock 数据，复制后会形成独立副本')}>浏览广场</Button>
      </div>
      <div className="studio-library-list">
        {items.map((skill) => (
          <SkillCard key={skill.id} skill={skill} onRun={() => runSkill(skill)} onDelete={skill.source === 'personal' ? () => deletePersonalSkill(skill.id) : undefined} />
        ))}
      </div>
      <Modal
        open={saveOpen}
        title="保存为个人 Skill"
        okText="保存"
        cancelText="取消"
        className="create-flow-modal"
        onCancel={() => setSaveOpen(false)}
        onOk={() => { savePersonalSkill(name, '基于当前成功管线保存的个人执行配方'); setSaveOpen(false); message.success('已保存到我的 Skill') }}
      >
        <Input value={name} onChange={(event) => setName(event.target.value)} />
      </Modal>
    </div>
  )
}

function SkillCard({ skill, onRun, onDelete }: { skill: StudioSkill; onRun: () => void; onDelete?: () => void }) {
  const share = async () => {
    const link = `${window.location.origin}/create/studio?skill=${encodeURIComponent(skill.id)}`
    await navigator.clipboard?.writeText(link)
    message.success('分享链接已复制；接收方复制后得到独立副本')
  }
  return (
    <article className="studio-library-card">
      <header><strong>{skill.name}</strong><span>{skill.source === 'preset' ? '预置' : '我的'}</span></header>
      <p>{skill.description}</p>
      <div>
        <Button size="small" type="primary" onClick={onRun}>本地编排</Button>
        <Button size="small" icon={<ShareAltOutlined />} onClick={() => void share()}>分享</Button>
        {onDelete ? <Button size="small" danger icon={<DeleteOutlined />} onClick={onDelete} /> : null}
      </div>
    </article>
  )
}

function NodeListPanel() {
  const [query, setQuery] = useState('')
  const nodes = useStudioUiStore((state) => state.nodes)
  const selectNodes = useStudioUiStore((state) => state.selectNodes)
  const activeNodeId = useStudioUiStore((state) => state.activeNodeId)
  const filtered = useMemo(() => nodes.filter((node) =>
    `${node.data.title}${STUDIO_NODE_META[node.data.kind].label}`.toLowerCase().includes(query.trim().toLowerCase())
  ), [nodes, query])
  const visible = filtered.slice(0, 250)

  return (
    <div className="studio-embedded-panel">
      <Input allowClear prefix={<SearchOutlined />} placeholder={`搜索 ${nodes.length} 个节点`} value={query} onChange={(event) => setQuery(event.target.value)} />
      <div className="studio-node-list">
        {visible.map((node) => (
          <button key={node.id} type="button" className={node.id === activeNodeId ? 'is-active' : ''} onClick={() => selectNodes([node.id])}>
            <span className="studio-node-list__icon">
              <StudioNodeIcon kind={node.data.kind} />
            </span>
            <span><strong>{node.data.title}</strong><small>{STUDIO_NODE_META[node.data.kind].label}</small></span>
            <StudioStatusBadge status={node.data.status} />
          </button>
        ))}
        {!filtered.length ? <div className="studio-panel-empty">没有匹配节点</div> : null}
        {filtered.length > visible.length ? <div className="studio-panel-empty">已显示前 {visible.length} 项，请搜索定位其余节点</div> : null}
      </div>
    </div>
  )
}
