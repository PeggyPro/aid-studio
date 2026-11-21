'use client'

/* eslint-disable @next/next/no-img-element -- 画布结果为服务端动态媒体地址，需直接支持图片和视频预览。 */

import {
  AppstoreOutlined,
  CheckOutlined,
  CloseOutlined,
  CopyOutlined,
  DeleteOutlined,
  HistoryOutlined,
  LinkOutlined,
  ReloadOutlined,
  SettingOutlined,
  UserAddOutlined,
  TeamOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import { Button, ConfigProvider, Empty, InputNumber, Modal, Pagination, Select, Space, Spin, Switch, Tag, message } from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { useRouter } from 'next/navigation'
import type { CanvasApplication, CanvasDraft, CanvasInvite, CanvasMember, CanvasProjectSetting, CanvasShare, CanvasTaskHistory } from '@/types/canvas-api'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { useStudioUiStore } from '@/stores/studioUi'
import {
  canvasApplicationList,
  canvasApplicationReview,
  canvasInviteCreate,
  canvasInviteList,
  canvasInviteRevoke,
  canvasMemberExit,
  canvasMemberList,
  canvasMemberRemove,
  canvasMemberRole,
  canvasProjectSettingList,
  canvasProjectSettingUpdate,
  canvasShareCreate,
  canvasShareList,
  canvasShareRevoke,
  canvasShareUpdate
} from '@/utils/business/canvas'
import { withAppBasePath } from '@/utils/appBasePath'
import { apiErrorMessage } from '@/utils/apiError'
import { canvasAccessUncertaintyStore, createCanvasAccessLinkSafely, type CanvasAccessUncertainty } from '@/utils/canvas/canvasAccessCreation'
import './canvas-project-panel.css'

type PanelTab = 'members' | 'node' | 'history' | 'limits' | 'access' | 'shares' | 'settings'

const CAPACITY_LABELS: Record<string, string> = {
  maxProjectsPerOwner: '可创建画布数',
  maxNodesPerWorkspace: '单画布节点数',
  maxMembersPerProject: '项目成员数',
  maxOnlineEditors: '同时在线编辑人数'
}

const ACCESS_STATUS_LABELS: Record<CanvasInvite['status'] | CanvasShare['status'], string> = {
  ACTIVE: '生效中',
  REVOKED: '已撤销',
  EXPIRED: '已过期'
}

function dateText(value?: string | null): string {
  if (!value) return '--'
  const date = new Date(value.replace(/-/g, '/'))
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function collaborationStatusText(runtime: CanvasProjectWorkspaceRuntime): string {
  if (runtime.collaborationStatus === 'connected') return '实时协作已连接'
  if (runtime.collaborationStatus === 'reconnecting') return '实时协作重连中'
  if (runtime.collaborationStatus === 'paused') return '实时协作已暂停'
  if (runtime.collaborationStatus === 'connecting') return '实时协作连接中'
  return '协作通道待连接'
}

async function writeClipboardText(value: string) {
  try {
    await navigator.clipboard.writeText(value)
  } catch {
    const input = document.createElement('textarea')
    input.value = value
    input.style.position = 'fixed'
    input.style.opacity = '0'
    const copyHost = document.querySelector('dialog.canvas-project-panel[open]') || document.body
    copyHost.appendChild(input)
    input.select()
    const copied = document.execCommand('copy')
    input.remove()
    if (!copied) throw new Error('复制失败')
  }
}

async function copySecretLink(kind: 'invite' | 'share', token: string, feedback: { success: (text: string) => unknown; error: (text: string) => unknown }) {
  const path = kind === 'invite' ? '/canvas/invite' : '/canvas/share'
  const link = `${window.location.origin}${withAppBasePath(path)}#token=${encodeURIComponent(token)}`
  try {
    await writeClipboardText(link)
    feedback.success(kind === 'invite' ? '邀请链接已复制' : '分享链接已复制')
  } catch {
    feedback.error('复制失败，请稍后重试')
  }
}

function NumberWithUnit({
  min,
  max,
  value,
  unit,
  onChange
}: {
  min: number
  max: number
  value: number
  unit: string
  onChange: (value: number) => void
}) {
  return (
    <Space.Compact>
      <InputNumber min={min} max={max} value={value} onChange={(next) => onChange(Number(next) || min)} />
      <Button disabled tabIndex={-1}>{unit}</Button>
    </Space.Compact>
  )
}

export function CanvasProjectPanel({
  runtime,
  open: controlledOpen,
  showTrigger = true,
  onOpenChange,
  returnFocusRef
}: {
  runtime: CanvasProjectWorkspaceRuntime
  open?: boolean
  showTrigger?: boolean
  onOpenChange?: (open: boolean) => void
  returnFocusRef?: RefObject<HTMLElement | null>
}) {
  const router = useRouter()
  const projectId = runtime.project ? String(runtime.project.projectId) : ''
  const dialogRef = useRef<HTMLDialogElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const [panelMessage, messageContextHolder] = message.useMessage({ getContainer: () => dialogRef.current || document.body })
  const panelMessageRef = useRef(panelMessage)
  useEffect(() => { panelMessageRef.current = panelMessage }, [panelMessage])
  const [internalOpen, setInternalOpen] = useState(false)
  const open = controlledOpen ?? internalOpen
  const [tab, setTab] = useState<PanelTab>('members')
  const [loading, setLoading] = useState(false)
  const [members, setMembers] = useState<CanvasMember[]>([])
  const [membersLoaded, setMembersLoaded] = useState(false)
  const [invites, setInvites] = useState<CanvasInvite[]>([])
  const [applications, setApplications] = useState<CanvasApplication[]>([])
  const [applicationsLoaded, setApplicationsLoaded] = useState(false)
  const [shares, setShares] = useState<CanvasShare[]>([])
  const [sharesLoaded, setSharesLoaded] = useState(false)
  const [settings, setSettings] = useState<CanvasProjectSetting[]>([])
  const [inviteRole, setInviteRole] = useState<'EDITOR' | 'VIEWER'>('EDITOR')
  const [inviteDays, setInviteDays] = useState(7)
  const [inviteUses, setInviteUses] = useState(10)
  const [newInviteToken, setNewInviteToken] = useState('')
  const [newShareToken, setNewShareToken] = useState('')
  const [uncertainInviteState, setUncertainInviteState] = useState<{ projectId: string; value: CanvasAccessUncertainty | null } | null>(null)
  const [uncertainShareState, setUncertainShareState] = useState<{ projectId: string; value: CanvasAccessUncertainty | null } | null>(null)
  const [shareDays, setShareDays] = useState(7)
  const [history, setHistory] = useState<CanvasTaskHistory | null>(null)
  const [historyPage, setHistoryPage] = useState(1)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [drafts, setDrafts] = useState<CanvasDraft[]>([])
  const [draftLoading, setDraftLoading] = useState(false)
  const [transferBusyId, setTransferBusyId] = useState('')
  const [transferredIds, setTransferredIds] = useState<string[]>([])
  const [pendingMutation, setPendingMutation] = useState('')
  const mutationRef = useRef('')
  const activeNodeId = useStudioUiStore((state) => state.activeNodeId)
  const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
  const activeNode = useStudioUiStore((state) => state.nodes.find((item) => item.id === state.activeNodeId))
  const project = runtime.project
  const uncertainInvite = uncertainInviteState?.projectId === projectId
    ? uncertainInviteState.value : canvasAccessUncertaintyStore.read(projectId, 'invite')
  const uncertainShare = uncertainShareState?.projectId === projectId
    ? uncertainShareState.value : canvasAccessUncertaintyStore.read(projectId, 'share')
  const setUncertainInvite = (value: CanvasAccessUncertainty | null) => {
    if (value) canvasAccessUncertaintyStore.write(projectId, 'invite', value)
    else canvasAccessUncertaintyStore.clear(projectId, 'invite')
    setUncertainInviteState({ projectId, value })
  }
  const setUncertainShare = (value: CanvasAccessUncertainty | null) => {
    if (value) canvasAccessUncertaintyStore.write(projectId, 'share', value)
    else canvasAccessUncertaintyStore.clear(projectId, 'share')
    setUncertainShareState({ projectId, value })
  }
  const owner = runtime.role === 'OWNER'
  const activeTab = owner || tab === 'members' || tab === 'node' || tab === 'history' || tab === 'limits' ? tab : 'members'
  const refreshCollaboration = runtime.refreshCollaboration
  const loadNodeHistory = runtime.loadNodeHistory
  const runtimeLoadDrafts = runtime.loadDrafts

  const setPanelOpen = (nextOpen: boolean) => {
    if (controlledOpen === undefined) setInternalOpen(nextOpen)
    onOpenChange?.(nextOpen)
  }

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  function closePanel() {
    if (dialogRef.current?.open) dialogRef.current.close()
    setPanelOpen(false)
    setNewInviteToken('')
    setNewShareToken('')
    const focusTarget = returnFocusRef?.current || triggerRef.current
    focusTarget?.focus()
  }

  function openPanel() {
    if (!dialogRef.current?.open) dialogRef.current?.showModal()
    setPanelOpen(true)
  }

  const loadMembers = useCallback(async () => {
    if (!projectId) return
    const data = await canvasMemberList(projectId)
    setMembers(data.items || [])
    setMembersLoaded(true)
  }, [projectId])

  const loadOwnerData = useCallback(async () => {
    if (!projectId || !owner) return
    const results = await Promise.allSettled([
      canvasInviteList(projectId),
      canvasApplicationList(projectId),
      canvasShareList(projectId),
      canvasProjectSettingList(projectId)
    ])
    const [inviteData, applicationData, shareData, settingData] = results
    if (inviteData.status === 'fulfilled') setInvites(inviteData.value.items || [])
    if (applicationData.status === 'fulfilled') {
      setApplications(applicationData.value.items || [])
      setApplicationsLoaded(true)
    }
    if (shareData.status === 'fulfilled') {
      setShares(shareData.value.items || [])
      setSharesLoaded(true)
    }
    if (settingData.status === 'fulfilled') setSettings(settingData.value.items || [])
    const failed = results.find((result) => result.status === 'rejected')
    if (failed?.status === 'rejected') throw failed.reason
  }, [owner, projectId])

  const reload = useCallback(async () => {
    if (!projectId) return
    setLoading(true)
    try {
      await Promise.all([loadMembers(), loadOwnerData(), refreshCollaboration()])
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '项目协作信息加载失败'))
    } finally {
      setLoading(false)
    }
  }, [loadMembers, loadOwnerData, panelMessage, projectId, refreshCollaboration])

  const previousOpenRef = useRef(false)
  useEffect(() => {
    const justOpened = open && !previousOpenRef.current
    previousOpenRef.current = open
    if (justOpened) void reload()
  }, [open, reload])

  const loadHistory = useCallback(async (pageNum = 1) => {
    if (!activeNodeId) {
      setHistory(null)
      setHistoryPage(1)
      return
    }
    setHistoryLoading(true)
    try {
      setHistory(await loadNodeHistory(activeNodeId, pageNum))
      setHistoryPage(pageNum)
    } catch (error: unknown) {
      panelMessageRef.current.error(apiErrorMessage(error, '节点生成历史加载失败'))
    } finally {
      setHistoryLoading(false)
    }
  }, [activeNodeId, loadNodeHistory])

  const loadDrafts = useCallback(async () => {
    setDraftLoading(true)
    try {
      setDrafts(await runtimeLoadDrafts())
    } catch (error: unknown) {
      panelMessageRef.current.error(apiErrorMessage(error, '个人草稿加载失败'))
    } finally {
      setDraftLoading(false)
    }
  }, [runtimeLoadDrafts])

  useEffect(() => {
    if (!open || tab !== 'node') return
    const timer = window.setTimeout(() => void loadDrafts(), 0)
    return () => window.clearTimeout(timer)
  }, [loadDrafts, open, tab])

  useEffect(() => {
    if (!open || activeTab !== 'history') return
    const timer = window.setTimeout(() => void loadHistory(1), 0)
    return () => window.clearTimeout(timer)
  }, [activeTab, loadHistory, open])

  const onlineIds = useMemo(
    () => new Set((runtime.collaboration?.onlineUsers || []).map((item) => String(item.userId))),
    [runtime.collaboration?.onlineUsers]
  )

  async function runMutation(key: string, action: () => Promise<void>) {
    if (mutationRef.current) return
    mutationRef.current = key
    setPendingMutation(key)
    try {
      await action()
    } finally {
      mutationRef.current = ''
      setPendingMutation('')
    }
  }

  async function changeRole(member: CanvasMember, roleCode: 'EDITOR' | 'VIEWER') {
    await runMutation(`role:${member.userId}`, async () => {
    try {
      await canvasMemberRole({ projectId, userId: String(member.userId), roleCode })
      await loadMembers()
      panelMessage.success('成员角色已更新')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '修改成员角色失败'))
    }
    })
  }

  function removeMember(member: CanvasMember) {
    Modal.confirm({
      getContainer: () => dialogRef.current || document.body,
      title: '移除成员',
      content: `确认将 ${member.nickName || member.userName || '该成员'} 移出画布吗？`,
      okText: '移除',
      cancelText: '取消',
      okType: 'danger',
      className: 'create-flow-modal',
      async onOk() {
        try {
          await canvasMemberRemove(projectId, String(member.userId))
          await Promise.all([loadMembers(), refreshCollaboration()])
          panelMessage.success('成员已移除')
        } catch (error: unknown) {
          panelMessage.error(apiErrorMessage(error, '移除成员失败'))
        }
      }
    })
  }

  async function createInvite() {
    if (uncertainInvite) return
    await runMutation('invite:create', async () => {
      const result = await createCanvasAccessLinkSafely(
        async () => (await canvasInviteList(projectId)).items || [],
        () => canvasInviteCreate({
        projectId,
        roleCode: inviteRole,
        validDays: inviteDays,
        maxUses: inviteUses
        })
      )
      if (result.status === 'created') {
        setNewInviteToken(result.value.inviteToken || '')
        await canvasInviteList(projectId).then((data) => setInvites(data.items || [])).catch(() => undefined)
        panelMessage.success('邀请已创建，请立即复制链接')
      } else if (result.status === 'uncertain') {
        if (result.rows) setInvites(result.rows)
        setUncertainInvite({ beforeIds: result.beforeIds, newActiveIds: result.newActiveIds })
        panelMessage.warning('邀请创建结果不确定，请核对新增记录；撤销无法找回链接的记录后再重试')
      } else {
        panelMessage.error(apiErrorMessage(result.error, '创建邀请失败'))
      }
    })
  }

  async function reconcileUncertainInvite() {
    if (!uncertainInvite) return
    try {
      const rows = (await canvasInviteList(projectId)).items || []
      setInvites(rows)
      const known = new Set(uncertainInvite.beforeIds)
      const newActiveIds = rows.filter((item) => item.status === 'ACTIVE' && !known.has(String(item.id)))
        .map((item) => String(item.id))
      if (newActiveIds.length) {
        setUncertainInvite({ ...uncertainInvite, newActiveIds })
        panelMessage.warning('发现新建但无法找回令牌的邀请，请先撤销该记录')
      } else {
        setUncertainInvite(null)
        panelMessage.success('已核对邀请记录，可以重新创建')
      }
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '邀请记录核对失败'))
    }
  }

  async function review(application: CanvasApplication, decision: 'APPROVE' | 'REJECT') {
    await runMutation(`application:${application.id}`, async () => {
    try {
      await canvasApplicationReview({ id: String(application.id), decision })
      const [applicationData, memberData, inviteData] = await Promise.all([
        canvasApplicationList(projectId),
        canvasMemberList(projectId),
        canvasInviteList(projectId)
      ])
      setApplications(applicationData.items || [])
      setMembers(memberData.items || [])
      setInvites(inviteData.items || [])
      panelMessage.success(decision === 'APPROVE' ? '已同意申请' : '已拒绝申请')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '审批申请失败'))
    }
    })
  }

  async function revokeInvite(id: string) {
    await runMutation(`invite:revoke:${id}`, async () => {
    try {
      await canvasInviteRevoke(projectId, id)
      const rows = (await canvasInviteList(projectId)).items || []
      setInvites(rows)
      if (uncertainInvite && !rows.some((item) => item.status === 'ACTIVE' && !uncertainInvite.beforeIds.includes(String(item.id)))) {
        setUncertainInvite(null)
      }
      panelMessage.success('邀请已撤销')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '撤销邀请失败'))
    }
    })
  }

  async function createShare() {
    if (uncertainShare) return
    await runMutation('share:create', async () => {
      const result = await createCanvasAccessLinkSafely(
        async () => (await canvasShareList(projectId)).items || [],
        () => canvasShareCreate({ projectId, validDays: shareDays })
      )
      if (result.status === 'created') {
        setNewShareToken(result.value.shareToken || '')
        await canvasShareList(projectId).then((data) => setShares(data.items || [])).catch(() => undefined)
        panelMessage.success('快照分享已创建，请立即复制链接')
      } else if (result.status === 'uncertain') {
        if (result.rows) setShares(result.rows)
        setUncertainShare({ beforeIds: result.beforeIds, newActiveIds: result.newActiveIds })
        panelMessage.warning('分享创建结果不确定，请核对新增记录；撤销无法找回链接的记录后再重试')
      } else {
        panelMessage.error(apiErrorMessage(result.error, '创建分享失败'))
      }
    })
  }

  async function reconcileUncertainShare() {
    if (!uncertainShare) return
    try {
      const rows = (await canvasShareList(projectId)).items || []
      setShares(rows)
      const known = new Set(uncertainShare.beforeIds)
      const newActiveIds = rows.filter((item) => item.status === 'ACTIVE' && !known.has(String(item.id)))
        .map((item) => String(item.id))
      if (newActiveIds.length) {
        setUncertainShare({ ...uncertainShare, newActiveIds })
        panelMessage.warning('发现新建但无法找回令牌的分享，请先撤销该记录')
      } else {
        setUncertainShare(null)
        panelMessage.success('已核对分享记录，可以重新创建')
      }
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '分享记录核对失败'))
    }
  }

  async function updateShare(id: string) {
    await runMutation(`share:update:${id}`, async () => {
    try {
      await canvasShareUpdate(projectId, id)
      setShares((await canvasShareList(projectId)).items || [])
      panelMessage.success('分享快照已刷新')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '刷新分享失败'))
    }
    })
  }

  async function revokeShare(id: string) {
    await runMutation(`share:revoke:${id}`, async () => {
    try {
      await canvasShareRevoke(projectId, id)
      const rows = (await canvasShareList(projectId)).items || []
      setShares(rows)
      if (uncertainShare && !rows.some((item) => item.status === 'ACTIVE' && !uncertainShare.beforeIds.includes(String(item.id)))) {
        setUncertainShare(null)
      }
      panelMessage.success('分享已撤销')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '撤销分享失败'))
    }
    })
  }

  const transferSetting = settings.find((item) => item.settingKey === 'asset.transfer.member.enabled')

  async function toggleTransfer(enabled: boolean) {
    if (!transferSetting) return
    await runMutation('setting:transfer', async () => {
    try {
      const updated = await canvasProjectSettingUpdate({
        projectId,
        settingKey: transferSetting.settingKey,
        expectedVersion: transferSetting.version,
        value: enabled
      })
      setSettings((current) => current.map((item) => item.settingKey === updated.settingKey ? updated : item))
      panelMessage.success('项目设置已保存')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '保存项目设置失败'))
      try {
        setSettings((await canvasProjectSettingList(projectId)).items || [])
      } catch {
        // 保留当前设置；原请求失败后的恢复请求不能继续冒泡为未处理 Promise。
      }
    }
    })
  }

  async function exitProject() {
    try {
      await canvasMemberExit(projectId)
      panelMessage.success('已退出画布')
      router.push('/works?tab=canvas')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '退出画布失败'))
    }
  }

  async function copyProjectId() {
    try {
      await writeClipboardText(projectId)
      panelMessage.success('画布 ID 已复制')
    } catch {
      panelMessage.error('复制画布 ID 失败')
    }
  }

  async function selectHistoryResult(resultId: string) {
    if (!activeNodeId) return
    setHistoryLoading(true)
    try {
      await runtime.selectNodeResult(activeNodeId, resultId)
      await loadHistory()
      panelMessage.success('已切换节点结果')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '切换节点结果失败'))
    } finally {
      setHistoryLoading(false)
    }
  }

  async function applyHistoryText(taskId: string, inputObjectVersion: number, textContent: string) {
    if (!activeNodeId) return
    setHistoryLoading(true)
    try {
      await runtime.applyNodeTextResult(activeNodeId, taskId, inputObjectVersion, textContent)
      await loadHistory(historyPage)
      panelMessage.success('文本结果已应用到节点')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '应用文本结果失败'))
    } finally {
      setHistoryLoading(false)
    }
  }

  async function saveCurrentDraft() {
    if (!activeNodeId) return
    setDraftLoading(true)
    try {
      const existing = drafts.find((draft) => String(draft.objectId) === activeNodeId)
      await runtime.saveNodeDraft(activeNodeId, existing?.draftVersion || 0)
      await loadDrafts()
      panelMessage.success('个人草稿已保存')
    } catch (error: unknown) {
      await loadDrafts()
      panelMessage.error(apiErrorMessage(error, '保存草稿失败，请核对版本'))
    } finally {
      setDraftLoading(false)
    }
  }

  async function removeDraft(draft: CanvasDraft) {
    setDraftLoading(true)
    try {
      await runtime.deleteNodeDraft(String(draft.objectId), draft.draftVersion)
      await loadDrafts()
      panelMessage.success('草稿已删除')
    } catch (error: unknown) {
      await loadDrafts()
      panelMessage.error(apiErrorMessage(error, '删除草稿失败'))
    } finally {
      setDraftLoading(false)
    }
  }

  function applyDraft(draft: CanvasDraft) {
    const prompt = draft.payload && typeof draft.payload === 'object' && !Array.isArray(draft.payload)
      ? String((draft.payload as Record<string, unknown>).prompt || '') : ''
    const apply = (acceptStale: boolean) => {
      try {
        runtime.applyNodeDraft(draft, acceptStale)
        panelMessage.success('草稿已载入编辑器，请检查后保存')
      } catch (error: unknown) {
        panelMessage.error(apiErrorMessage(error, '载入草稿失败'))
      }
    }
    if (!draft.stale) { apply(false); return }
    const sharedPrompt = useStudioUiStore.getState().nodes.find((node) => node.id === String(draft.objectId))?.data.prompt || ''
    Modal.confirm({
      getContainer: () => dialogRef.current || document.body,
      title: '共享节点已更新',
      className: 'canvas-project-panel-confirm create-flow-modal',
      okText: '采用个人草稿',
      cancelText: '保留共享内容',
      content: <div className="canvas-draft-compare"><strong>当前共享提示词</strong><pre>{sharedPrompt}</pre><strong>个人草稿提示词</strong><pre>{prompt}</pre><small>采用草稿会先放入本地编辑器，保存时仍由服务端校验最新节点版本。</small></div>,
      onOk: () => apply(true)
    })
  }

  async function transfer(resourceId: string, assetName: string) {
    if (transferBusyId) return
    setTransferBusyId(resourceId)
    try {
      await runtime.transferResource(resourceId, assetName)
      setTransferredIds((items) => items.includes(resourceId) ? items : [...items, resourceId])
      panelMessage.success('素材已转存到个人资产库')
    } catch (error: unknown) {
      panelMessage.error(apiErrorMessage(error, '转存素材失败'))
    } finally {
      setTransferBusyId('')
    }
  }

  async function copySelection() {
    await runMutation('node:copy', async () => {
      try {
        await runtime.copyNodes(selectedNodeIds.length ? selectedNodeIds : activeNodeId ? [activeNodeId] : [])
        panelMessage.success('节点已复制')
      } catch (error: unknown) {
        panelMessage.error(apiErrorMessage(error, '复制节点失败'))
      }
    })
  }

  async function undoCanvas() {
    await runMutation('node:undo', async () => {
      try {
        await runtime.undoLast()
        panelMessage.success('上一次已保存操作已撤销')
      } catch (error: unknown) {
        panelMessage.error(apiErrorMessage(error, '撤销失败，可能有其他成员更新了对象'))
      }
    })
  }

  const tabs: Array<{ key: PanelTab; label: string; icon: React.ReactNode; ownerOnly?: boolean }> = [
    { key: 'members', label: '成员管理', icon: <TeamOutlined /> },
    { key: 'node', label: '节点工具', icon: <AppstoreOutlined /> },
    { key: 'history', label: '节点历史', icon: <HistoryOutlined /> },
    { key: 'limits', label: '有效权益', icon: <ThunderboltOutlined /> },
    { key: 'access', label: '邀请与审批', icon: <UserAddOutlined />, ownerOnly: true },
    { key: 'shares', label: '分享管理', icon: <LinkOutlined />, ownerOnly: true },
    { key: 'settings', label: '权限设置', icon: <SettingOutlined />, ownerOnly: true }
  ]
  const visibleTabs = tabs.filter((item) => owner || !item.ownerOnly)
  const pendingApplications = applications.filter((item) => item.status === 'PENDING').length
  const activeShares = shares.filter((item) => item.status === 'ACTIVE').length
  const historyRows = (history?.items || []).reduce<Array<{
    task: CanvasTaskHistory['items'][number]
    result: CanvasTaskHistory['items'][number]['results'][number] | null
  }>>((rows, task) => {
    if (task.results.length) rows.push(...task.results.map((result) => ({ task, result })))
    else rows.push({ task, result: null })
    return rows
  }, [])
  const selectedResources = activeNodeId ? runtime.nodeResources(activeNodeId) : []

  return (
    <>
      {showTrigger ? <Button
        ref={triggerRef}
        icon={<TeamOutlined />}
        className="canvas-project-panel-trigger"
        onClick={openPanel}
      >
        协作
        {runtime.collaboration?.onlineUsers?.length ? <em>{runtime.collaboration.onlineUsers.length}</em> : null}
      </Button> : null}
      <dialog
        ref={dialogRef}
        className="canvas-project-panel"
        aria-labelledby="canvas-project-panel-title"
        onCancel={(event) => { event.preventDefault(); closePanel() }}
      >
        <ConfigProvider getPopupContainer={() => dialogRef.current || document.body}>
          {messageContextHolder}
          <div className="canvas-project-panel__layout">
            <aside className="canvas-project-panel__sidebar">
              <nav className="canvas-project-panel__tabs" role="tablist" aria-label="画布项目管理">
                {visibleTabs.map((item, index) => (
                  <button
                    key={item.key}
                    id={`canvas-project-panel-tab-${item.key}`}
                    type="button"
                    role="tab"
                    aria-selected={activeTab === item.key}
                    aria-controls="canvas-project-panel-view"
                    tabIndex={activeTab === item.key ? 0 : -1}
                    className={activeTab === item.key ? 'is-active' : ''}
                    onClick={() => setTab(item.key)}
                    onKeyDown={(event) => {
                      const next = event.key === 'ArrowDown' ? (index + 1) % visibleTabs.length
                        : event.key === 'ArrowUp' ? (index - 1 + visibleTabs.length) % visibleTabs.length
                          : event.key === 'Home' ? 0 : event.key === 'End' ? visibleTabs.length - 1 : -1
                      if (next < 0) return
                      event.preventDefault()
                      setTab(visibleTabs[next].key)
                      document.getElementById(`canvas-project-panel-tab-${visibleTabs[next].key}`)?.focus()
                    }}
                  >
                    {item.icon}<span>{item.label}</span>
                  </button>
                ))}
              </nav>
            </aside>
            <div className="canvas-project-panel__content">
              <header className="canvas-project-panel__header">
                <span className="canvas-project-panel__brand" aria-hidden="true">{(project?.projectName || '画').slice(0, 1)}</span>
                <div className="canvas-project-panel__title">
                  <h2 id="canvas-project-panel-title">{project?.projectName || '画布项目'} <Tag>{owner ? '所有者' : runtime.role === 'EDITOR' ? '可编辑' : '仅查看'}</Tag></h2>
                  <small>画布 ID：{projectId} <button type="button" className="canvas-project-panel__copy-id" aria-label="复制画布 ID" onClick={() => void copyProjectId()}><CopyOutlined /></button> · {collaborationStatusText(runtime)}{runtime.collaborationError ? ` · ${runtime.collaborationError}` : ''}</small>
                </div>
                {runtime.collaborationStatus === 'paused' ? (
                  <Button size="small" type="text" icon={<LinkOutlined />} onClick={runtime.retryCollaboration}>重连协作</Button>
                ) : null}
                <Button type="text" icon={<ReloadOutlined />} loading={loading} onClick={() => void reload()} aria-label="刷新协作信息" />
                <button type="button" className="canvas-project-panel__close" aria-label="关闭协作管理" onClick={closePanel}><CloseOutlined /></button>
              </header>
              <div id="canvas-project-panel-view" className="canvas-project-panel__scroll" role="tabpanel" aria-labelledby={`canvas-project-panel-tab-${activeTab}`}>
                <div className="canvas-project-panel__overview" aria-label="协作概览">
                  <div><span>项目成员</span><strong>{membersLoaded ? members.length : '—'}</strong><small>{runtime.entitlements?.effectiveLimits?.maxMembersPerProject == null ? '当前有效成员' : `成员名额上限 ${runtime.entitlements.effectiveLimits.maxMembersPerProject}`}</small></div>
                  <div><span>在线协作</span><strong>{runtime.collaboration?.onlineUsers?.length ?? '—'}</strong><small>{runtime.connected ? '已连接' : '未连接'}</small></div>
                  <div><span>待审批申请</span><strong>{owner && applicationsLoaded ? pendingApplications : '—'}</strong><small>{owner ? '需要处理的申请' : '仅所有者可查看'}</small></div>
                  <div><span>有效分享</span><strong>{owner && sharesLoaded ? activeShares : '—'}</strong><small>{owner ? '可访问的快照' : '仅所有者可查看'}</small></div>
                </div>
                <Spin spinning={loading}>
          {activeTab === 'members' ? (
            <section className="canvas-project-panel__section">
              <div className="canvas-project-panel__section-head"><h3>全部成员</h3><span>{membersLoaded ? `${members.length} 人` : '未加载'}</span></div>
              <div className="canvas-project-panel__member-table" role="table" aria-label="画布成员">
                <div className="canvas-project-panel__member-table-head" role="row">
                  <span role="columnheader">成员</span><span role="columnheader">用户 ID</span><span role="columnheader">账号</span><span role="columnheader">加入时间</span><span role="columnheader">角色</span><span role="columnheader">状态</span><span role="columnheader">操作</span>
                </div>
                {members.map((member) => (
                  <div className="canvas-member-row" role="row" key={String(member.id)}>
                    <span className="canvas-member-row__member" role="cell">
                      <span className={`canvas-member-row__avatar${onlineIds.has(String(member.userId)) ? ' is-online' : ''}`}>
                        {member.avatarUrl ? <img src={member.avatarUrl} alt="" /> : (member.nickName || member.userName || '?').slice(0, 1)}
                      </span>
                      <strong>{member.nickName || member.userName || '未命名成员'}</strong>
                    </span>
                    <span role="cell" title={String(member.userId)}>{String(member.userId)}</span>
                    <span role="cell" title={member.userName || ''}>{member.userName || '—'}</span>
                    <span role="cell">{dateText(member.joinedTime)}</span>
                    <span role="cell">{member.roleCode === 'OWNER' ? <Tag color="blue">所有者</Tag> : owner ? (
                        <Select value={member.roleCode} disabled={Boolean(pendingMutation)} aria-label={`修改 ${member.nickName || member.userName || '成员'} 的角色`} options={[{ label: '可编辑', value: 'EDITOR' }, { label: '仅查看', value: 'VIEWER' }]} onChange={(value) => void changeRole(member, value)} />
                      ) : <Tag>{member.roleCode === 'EDITOR' ? '可编辑' : '仅查看'}</Tag>}</span>
                    <span role="cell">{onlineIds.has(String(member.userId)) ? '在线' : '离线'}</span>
                    <span role="cell">{owner && member.roleCode !== 'OWNER' ? <Button type="text" danger icon={<DeleteOutlined />} onClick={() => removeMember(member)} aria-label={`移除 ${member.nickName || member.userName || '成员'}`} /> : '—'}</span>
                  </div>
                ))}
                {membersLoaded && !members.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无成员" /> : null}
                {!membersLoaded && !loading ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="成员信息未加载，请刷新" /> : null}
              </div>
              {!owner ? <Button danger className="canvas-project-panel__exit" onClick={() => void exitProject()}>退出该画布</Button> : null}
            </section>
          ) : null}

          {activeTab === 'node' ? (
            <section className="canvas-project-panel__section">
              <div className="canvas-project-panel__section-head"><h3>节点操作</h3><span>{activeNode?.data.title || '未选中节点'}</span></div>
              <div className="canvas-node-actions">
                <Button disabled={!runtime.canMutate || !activeNodeId || Boolean(pendingMutation)} loading={pendingMutation === 'node:copy'} onClick={() => void copySelection()}>复制选中节点</Button>
                <Button disabled={!runtime.canMutate || !runtime.undoAvailable || Boolean(pendingMutation)} loading={pendingMutation === 'node:undo'} onClick={() => void undoCanvas()}>撤销上次已保存操作</Button>
              </div>
              <div className="canvas-project-panel__section-head"><h3>个人草稿</h3><Button size="small" type="text" icon={<ReloadOutlined />} loading={draftLoading} onClick={() => void loadDrafts()}>刷新</Button></div>
              {runtime.role !== 'VIEWER' && activeNodeId ? <Button className="canvas-draft-save" loading={draftLoading} onClick={() => void saveCurrentDraft()}>保存当前节点为个人草稿</Button> : null}
              <div className="canvas-project-panel__list">
                {drafts.map((draft) => {
                  const payload = draft.payload && typeof draft.payload === 'object' && !Array.isArray(draft.payload) ? draft.payload as Record<string, unknown> : {}
                  const title = useStudioUiStore.getState().nodes.find((node) => node.id === String(draft.objectId))?.data.title || String(draft.objectId)
                  return <div className="canvas-draft-row" key={String(draft.objectId)}>
                    <div className="canvas-draft-row__heading"><strong>{title}</strong>{draft.stale ? <Tag color="orange">共享内容已变化</Tag> : <Tag>个人草稿</Tag>}</div>
                    <pre>{String(payload.prompt || payload.documentContent || '空草稿')}</pre>
                    <div className="canvas-draft-row__actions">
                      <Button size="small" disabled={runtime.role === 'VIEWER' || draftLoading} onClick={() => applyDraft(draft)}>{draft.stale ? '比较并载入' : '载入编辑器'}</Button>
                      <Button size="small" danger disabled={draftLoading} onClick={() => void removeDraft(draft)}>删除</Button>
                    </div>
                  </div>
                })}
                {!drafts.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无个人草稿" /> : null}
              </div>
              {activeNodeId ? <>
                <div className="canvas-project-panel__section-head"><h3>节点素材</h3><span>{selectedResources.length} 项</span></div>
                <div className="canvas-project-panel__list">
                  {selectedResources.map((resource) => <div className="canvas-setting-row" key={String(resource.resourceId)}>
                    <span className="canvas-project-panel__row-copy"><strong>{resource.fileName || `${resource.mediaType} 素材`}</strong><small>素材编号 {resource.resourceId}</small></span>
                    {transferredIds.includes(String(resource.resourceId)) ? <Tag color="green">已转存</Tag> : <Button size="small" disabled={runtime.role === 'VIEWER' || resource.mediaType === 'AUDIO' || resource.mediaType === 'DOCUMENT' || Boolean(transferBusyId)} loading={transferBusyId === String(resource.resourceId)} onClick={() => void transfer(String(resource.resourceId), resource.fileName || activeNode?.data.title || '画布素材')}>转存</Button>}
                  </div>)}
                  {!selectedResources.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="节点暂无已登记素材" /> : null}
                </div>
              </> : null}
            </section>
          ) : null}

          {activeTab === 'history' ? (
            <section className="canvas-project-panel__section">
              <div className="canvas-project-panel__section-head">
                <h3>{activeNode ? `${activeNode.data.title} · 生成历史` : '节点生成历史'}</h3>
                {activeNodeId ? <Button size="small" type="text" icon={<ReloadOutlined />} loading={historyLoading} onClick={() => void loadHistory(historyPage)}>刷新</Button> : null}
              </div>
              {!activeNodeId ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先在画布中选中一个节点" /> : (
                <Spin spinning={historyLoading}>
                  <div className="canvas-history-list">
                    {historyRows.map(({ task, result }, index) => {
                      const mediaType = String(result?.mediaType || task.mediaType || '').toUpperCase()
                      const textContent = result?.textContent == null ? '' : String(result.textContent)
                      const failed = String(task.status).toUpperCase() === 'FAILED'
                      const currentText = mediaType === 'TEXT'
                        && String(activeNode?.data.canvasCurrentTaskId || '') === String(result?.taskId || task.taskId || '')
                        && activeNode?.data.documentContent === textContent
                      return (
                        <article
                          className={`canvas-history-result${mediaType === 'TEXT' ? ' is-text' : ''}${failed ? ' is-error' : ''}`}
                          key={`${String(task.operationId || task.taskId || 'task')}-${String(result?.resultId || index)}`}
                        >
                          <div className="canvas-history-result__preview">
                            {mediaType === 'TEXT' && textContent ? <pre>{textContent}</pre>
                              : result?.resultUrl ? (mediaType === 'VIDEO'
                                  ? <video src={result.resultUrl} muted playsInline preload="metadata" />
                                  : mediaType === 'AUDIO' ? <audio src={result.resultUrl} controls preload="none" />
                                  : mediaType === 'FILE' || mediaType === 'DOCUMENT' ? <a href={result.resultUrl} download rel="noreferrer">下载附属文件</a>
                                  : <img src={result.resultUrl} alt="生成结果" />)
                                : <span>{failed ? (task.userMessage || task.errorMessage || '生成失败') : '结果处理中'}</span>}
                          </div>
                          <div className="canvas-history-result__meta">
                            <strong>{mediaType === 'TEXT' ? '文本结果' : mediaType === 'VIDEO' ? '视频结果' : mediaType === 'AUDIO' ? '音频结果' : mediaType === 'FILE' || mediaType === 'DOCUMENT' ? '附属文件' : '图片结果'}</strong>
                            <small>{dateText(task.updateTime || task.createTime)}</small>
                            {failed && (task.userMessage || task.errorMessage) ? <small className="is-error">{task.userMessage || task.errorMessage}</small> : null}
                          </div>
                          {mediaType === 'TEXT' && textContent ? (currentText
                            ? <Tag color="blue">已应用</Tag>
                            : <Button
                                size="small"
                                disabled={!runtime.canMutate || task.inputObjectVersion == null}
                                onClick={() => void applyHistoryText(String(result?.taskId || task.taskId), Number(task.inputObjectVersion), textContent)}
                              >应用到节点</Button>)
                            : result?.selected ? <Tag color="blue">当前结果</Tag> : result?.resultId && (activeNode?.data.kind !== 'voice' || mediaType === 'AUDIO') ? (
                              <Button size="small" disabled={!runtime.canMutate} onClick={() => void selectHistoryResult(String(result.resultId))}>设为当前</Button>
                            ) : failed ? <Tag color="red">失败</Tag> : null}
                        </article>
                      )
                    })}
                    {(history?.runtimeRuns || []).map((run) => <article className={`canvas-history-result is-text${run.status === 'FAILED' ? ' is-error' : ''}`} key={`run-${run.runId}`}>
                      <div className="canvas-history-result__preview">
                        {run.resultPreview?.textContent ? <pre>{run.resultPreview.textContent}</pre> : <span>{run.status === 'FAILED' ? '运行失败，请查看节点能力面板' : `Agent / Skill 运行 ${run.status}`}</span>}
                      </div>
                      <div className="canvas-history-result__meta"><strong>{run.memberType} 运行</strong><small>{dateText(run.updateTime || run.createTime)}</small><small>运行 ID {run.runId}</small></div>
                      {run.status === 'SUCCEEDED' && String(run.mediaType).toUpperCase() === 'TEXT' ? <Button size="small" disabled={!runtime.canEdit} onClick={() => void runtime.adoptRun(activeNodeId, String(run.runId), 'APPEND').then(() => panelMessage.success('已追加到正文')).catch((error) => panelMessage.error(apiErrorMessage(error, '采纳失败')))}>追加正文</Button> : <Tag>{run.status}</Tag>}
                    </article>)}
                    {!historyRows.length && !history?.runtimeRuns?.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无生成记录" /> : null}
                  </div>
                  {(history?.total || 0) > 20 ? (
                    <Pagination
                      className="canvas-history-pagination"
                      current={historyPage}
                      pageSize={20}
                      total={history?.total || 0}
                      showSizeChanger={false}
                      disabled={historyLoading}
                      onChange={(page) => void loadHistory(page)}
                    />
                  ) : null}
                </Spin>
              )}
            </section>
          ) : null}

          {activeTab === 'limits' ? (
            <section className="canvas-project-panel__section">
              <h3>当前用户能力</h3>
              <div className="canvas-project-panel__list">
                {(runtime.entitlements?.items || []).map((item) => <div className="canvas-setting-row" key={item.entitlementCode}><span className="canvas-project-panel__row-copy"><strong>{item.name || item.entitlementCode}</strong></span><Tag color={item.enabled ? 'green' : 'default'}>{item.enabled ? '可用' : '不可用'}</Tag></div>)}
              </div>
              <h3>项目有效容量</h3>
              <div className="canvas-project-panel__list">
                {Object.entries(runtime.entitlements?.effectiveLimits || {}).map(([name, limit]) => <div className="canvas-setting-row" key={name}><span className="canvas-project-panel__row-copy"><strong>{CAPACITY_LABELS[name] || name}</strong><small>当前画布生效的容量上限</small></span><Tag>{limit}</Tag></div>)}
              </div>
              {!runtime.entitlements ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="权益暂时不可用，请稍后刷新画布" /> : null}
            </section>
          ) : null}

          {owner && activeTab === 'access' ? (
            <section className="canvas-project-panel__section">
              <h3>创建邀请</h3>
              <div className="canvas-project-panel__form-row">
                <Select value={inviteRole} onChange={setInviteRole} options={[{ label: '可编辑', value: 'EDITOR' }, { label: '仅查看', value: 'VIEWER' }]} />
                <NumberWithUnit min={1} max={365} value={inviteDays} unit="天" onChange={setInviteDays} />
                <NumberWithUnit min={1} max={1000} value={inviteUses} unit="次" onChange={setInviteUses} />
                <Button type="primary" loading={pendingMutation === 'invite:create'} disabled={Boolean(pendingMutation) || Boolean(uncertainInvite)} onClick={() => void createInvite()}>生成邀请</Button>
              </div>
              {uncertainInvite ? <div className="canvas-project-panel__uncertain" role="alert">上次邀请可能已创建，列表无法找回邀请令牌。请重新核对；若发现“待核对”的记录，先撤销后再创建。<Button size="small" onClick={() => void reconcileUncertainInvite()}>重新核对</Button></div> : null}
              {newInviteToken ? <Button block icon={<CopyOutlined />} onClick={() => void copySecretLink('invite', newInviteToken, panelMessage)}>复制本次邀请链接（关闭后不可找回）</Button> : null}
              <h3>待审批申请</h3>
              <div className="canvas-project-panel__list">
                {applications.filter((item) => item.status === 'PENDING').map((application) => (
                  <div className="canvas-access-row" key={String(application.id)}>
                    <span className="canvas-project-panel__row-copy"><strong>{application.nickName || application.userName || '申请人'}</strong><small>{application.applyMessage || '未填写申请说明'}</small></span>
                    <Tag>{application.requestedRole === 'EDITOR' ? '可编辑' : '仅查看'}</Tag>
                    <Button size="small" type="primary" icon={<CheckOutlined />} loading={pendingMutation === `application:${application.id}`} disabled={Boolean(pendingMutation)} onClick={() => void review(application, 'APPROVE')}>同意</Button>
                    <Button size="small" icon={<CloseOutlined />} disabled={Boolean(pendingMutation)} onClick={() => void review(application, 'REJECT')}>拒绝</Button>
                  </div>
                ))}
                {!applications.some((item) => item.status === 'PENDING') ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待审批申请" /> : null}
              </div>
              <h3>邀请记录</h3>
              <div className="canvas-project-panel__list">
                {invites.map((invite) => (
                  <div className="canvas-access-row" key={String(invite.id)}>
                    <span className="canvas-project-panel__row-copy"><strong>{invite.roleCode === 'EDITOR' ? '可编辑邀请' : '仅查看邀请'}</strong><small>{dateText(invite.expireTime)} 到期 · 剩余 {invite.remainingUses} 次</small></span>
                    {uncertainInvite?.newActiveIds.includes(String(invite.id)) ? <Tag color="warning">待核对</Tag> : null}
                    <Tag color={invite.status === 'ACTIVE' ? 'green' : 'default'}>{ACCESS_STATUS_LABELS[invite.status] ?? invite.status}</Tag>
                    {invite.status === 'ACTIVE' ? <Button size="small" danger loading={pendingMutation === `invite:revoke:${invite.id}`} disabled={Boolean(pendingMutation)} onClick={() => void revokeInvite(String(invite.id))}>撤销</Button> : null}
                  </div>
                ))}
              </div>
            </section>
          ) : null}

          {owner && activeTab === 'shares' ? (
            <section className="canvas-project-panel__section">
              <h3>创建只读快照</h3>
              <div className="canvas-project-panel__form-row">
                <NumberWithUnit min={1} max={365} value={shareDays} unit="天" onChange={setShareDays} />
                <Button type="primary" icon={<LinkOutlined />} loading={pendingMutation === 'share:create'} disabled={Boolean(pendingMutation) || Boolean(uncertainShare)} onClick={() => void createShare()}>创建分享</Button>
              </div>
              {uncertainShare ? <div className="canvas-project-panel__uncertain" role="alert">上次分享可能已创建，列表无法找回分享令牌。请重新核对；若发现“待核对”的记录，先撤销后再创建。<Button size="small" onClick={() => void reconcileUncertainShare()}>重新核对</Button></div> : null}
              {newShareToken ? <Button block icon={<CopyOutlined />} onClick={() => void copySecretLink('share', newShareToken, panelMessage)}>复制本次分享链接（关闭后不可找回）</Button> : null}
              <h3>分享记录</h3>
              <div className="canvas-project-panel__list">
                {shares.map((share) => (
                  <div className="canvas-access-row" key={String(share.id)}>
                    <span className="canvas-project-panel__row-copy"><strong>版本 {share.snapshotRevision}</strong><small>{dateText(share.expireTime)} 到期</small></span>
                    {uncertainShare?.newActiveIds.includes(String(share.id)) ? <Tag color="warning">待核对</Tag> : null}
                    <Tag color={share.status === 'ACTIVE' ? 'green' : 'default'}>{ACCESS_STATUS_LABELS[share.status] ?? share.status}</Tag>
                    {share.status === 'ACTIVE' ? <><Button size="small" loading={pendingMutation === `share:update:${share.id}`} disabled={Boolean(pendingMutation)} onClick={() => void updateShare(String(share.id))}>刷新快照</Button><Button size="small" danger loading={pendingMutation === `share:revoke:${share.id}`} disabled={Boolean(pendingMutation)} onClick={() => void revokeShare(String(share.id))}>撤销</Button></> : null}
                  </div>
                ))}
                {!shares.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无分享记录" /> : null}
              </div>
            </section>
          ) : null}

          {owner && activeTab === 'settings' ? (
            <section className="canvas-project-panel__section">
              <h3>素材转存权限</h3>
              <div className="canvas-setting-row">
                <span className="canvas-project-panel__row-copy"><strong>允许项目成员转存素材</strong><small>开启后，编辑成员可将画布素材转存到自己的素材库。</small></span>
                <Switch checked={Boolean(transferSetting?.value)} loading={pendingMutation === 'setting:transfer'} disabled={!transferSetting || Boolean(pendingMutation)} onChange={(value) => void toggleTransfer(value)} />
              </div>
            </section>
          ) : null}
                </Spin>
              </div>
            </div>
          </div>
        </ConfigProvider>
      </dialog>
    </>
  )
}
