'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import dynamic from 'next/dynamic'
import { OrthographicCamera, PerspectiveCamera, Vector3 } from 'three'
import { applyCommands, setKey, shotAt, uid } from '@/utils/previz/core'
import { PREVIZ_DIRECTOR_VIEW_ID } from '@/utils/previz/nativeCameras'
import { positionPathKeys } from '@/utils/previz/interaction'
import type { PrevizNode, PrevizProject, Vec3 } from '@/types/previz'
import { App, Button, Dropdown, Input, Segmented, Tooltip } from 'antd'
import { CloseOutlined, EllipsisOutlined, ExportOutlined, ImportOutlined, RedoOutlined, SaveOutlined, UndoOutlined } from '@ant-design/icons'
import { PrevizModalProvider } from '@/components/previz/PrevizModalProvider'
import { DirectorActionLibraryDialog } from './DirectorActionLibraryDialog'
import { useStudioUiStore } from '@/stores/studioUi'
import type { DirectorCamera, DirectorCapture, DirectorSceneData, DirectorTransform, DirectorViewMode } from '@/types/studio'
import {
  DIRECTOR_CAMERA_PRESETS,
  cloneDirectorScene,
  createDefaultDirectorScene,
  createDirectorActor,
  createDirectorCameraBookmark,
  createDirectorProp,
  normalizeDirectorScene,
  resolveDirectorAspectRatio,
  resolveDirectorObjectSpawnPosition
} from '@/utils/studio/directorScene'
import { applyDirectorGroupedTransform, listDirectorCrowdMemberIds, prioritizeUnlockedDirectorSelection, resolveDirectorCrowdGroupId, resolveDirectorViewportSelection } from './directorCrowdGroup'
import type { DirectorViewportSelectOptions } from './DirectorViewport'
import { persistentDirectorScene } from '@/utils/studio/directorApi'
import { pruneDirectorPaths } from '@/utils/studio/directorPathClips'
import { findDirectorPrevizVideo, openDirectorFinishComposer } from '@/utils/studio/directorCanvasBridge'
import { DirectorPrevizToolbar } from './DirectorPrevizToolbar'
import { DirectorSceneryRuntime } from './DirectorSceneryRuntime'
import { DirectorPathDrawOverlay, insertDirectorWaypoint } from './DirectorPathEditor'
import { DirectorMonitorPip } from './DirectorMonitorPip'
import { DirectorNameplates } from './DirectorNameplates'
import { studioId } from '@/utils/studio/studioGraph'
import { DirectorBottomBar, type DirectorTransformMode } from './DirectorBottomBar'
import { DirectorInspector } from './DirectorInspector'
import { DirectorSidebar, type DirectorSidebarTool } from './DirectorSidebar'
import { DirectorStageLoading, DirectorStageRuntimeError } from './DirectorStageTransition'
import { DirectorViewport, type DirectorViewportHandle } from './DirectorViewport'
import { downloadThreePrevizProject, readThreePrevizProject } from '@/utils/studio/threePrevizProjectIo'
import { applyThreePrevizProject, threePrevizDuration, threePrevizPlaybackEnd, threePrevizObjectCount, threePrevizHistoryFingerprint, projectThreePreviz, hasMissingThreePrevizBinding } from '@/utils/studio/threePrevizDocument'
import { saveThreePrevizLocal, ThreePrevizLocalConflict } from '@/utils/studio/threePrevizStorage'
import type { CanvasSceneAdoption } from '@/types/canvas-api'
import { useThreePrevizSession } from '@/hooks/studio/useThreePrevizSession'
import { DirectorDocumentLoader, type DirectorLoadedDocument } from './DirectorDocumentLoader'
import { DirectorModelingTools } from './DirectorModelingPanel'
import { activateDirectorCamera, isDirectorCameraLocked, listDirectorAuthoredCameras, resolveDirectorActiveCamera, resolveDirectorActiveCameraId, resolveDirectorCamera, resolveDirectorCameraLens, updateDirectorCameraAimObjectId, updateDirectorCameraEntity, updateDirectorCameraLens } from './directorCameraState'
import { isDirectorCameraSelection } from './directorInspectorState'
import { appendDirectorShotCaptures, createDirectorShotCapture, resolveDirectorBatchShotPlan, resolveDirectorShotCanvasTitle } from './directorBatchShots'
import { normalizeDirectorTimeline } from '@/utils/studio/directorTimeline'
import { useDirectorPlaybackStore } from './directorPlaybackStore'
import { DirectorCameraSetupModal, type DirectorCameraSetupRequest, type DirectorCameraSetupResult } from './DirectorCameraSetupModal'
import { clearDirectorCameraPreview, previewDirectorCamera } from './directorCameraPreviewStore'
import { syncDirectorLensToCameraFov } from '@/utils/studio/directorLens'
import { DirectorCameraViewportHud } from './DirectorCameraViewportHud'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import { canvasResourceDisplayUrl } from '@/utils/canvas/canvasPublicationFiles'
import { stableCanvasJson } from '@/utils/canvas/canvasWorkspaceAdapter'
import { isDirectorPanoramaFile } from './directorPanorama'
import { useDirectorManagedResourceRefresh } from './useDirectorManagedResourceRefresh'

function snapshotForHistory(scene: DirectorSceneData): DirectorSceneData {
  const { modeling, ...base } = scene
  // Modeling commands replace the graph. Immutable assets can be shared by undo entries.
  return { ...cloneDirectorScene(base), modeling }
}

const DirectorModelingRuntime = dynamic(() => import('./DirectorModelingRuntime').then(module => module.DirectorModelingRuntime), { ssr: false })

export default function DirectorStage(props: { nodeId: string }) {
  return <PrevizModalProvider><DirectorDocumentLoader nodeId={props.nodeId}>{document => <DirectorStageContent key={document.key} {...props} document={document} />}</DirectorDocumentLoader></PrevizModalProvider>
}

function DirectorStageContent({ nodeId, document }: { nodeId: string; document: DirectorLoadedDocument }) {
  const { message } = App.useApp()
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  const storedScenes = useStudioUiStore((state) => state.directorScenes)
  const nodes = useStudioUiStore((state) => state.nodes)
  const edges = useStudioUiStore((state) => state.edges)
  const setDirectorScene = useStudioUiStore((state) => state.setDirectorScene)
  const updateNodeData = useStudioUiStore((state) => state.updateNodeData)
  const addDirectorCapture = useStudioUiStore((state) => state.addDirectorCapture)
  const addDirectorCaptures = useStudioUiStore((state) => state.addDirectorCaptures)
  const canvasRuntime = useCanvasProjectRuntime()
  const scopeKey = useStudioUiStore((state) => state.scopeKey)
  const closeDirector = useStudioUiStore((state) => state.closeDirector)
  const [scene, setScene] = useState<DirectorSceneData>(() => {
    const sceneId = node?.data.directorSceneId
    const source = document.recovered ?? (sceneId ? storedScenes[sceneId] ?? createDefaultDirectorScene(sceneId) : createDefaultDirectorScene())
    const initial = normalizeDirectorScene(source)
    const knownSources = new Set(initial.sourceNodeIds ?? [])
    const linkedSources = new Set(edges.filter((edge) => edge.target === nodeId).map((edge) => edge.source))
    const sources = nodes.filter(item => linkedSources.has(item.id) && !knownSources.has(item.id))
    initial.sourceNodeIds = [...new Set([...knownSources, ...linkedSources])]
    for (const sourceNode of sources) {
      if (initial.objects.some((object) => object.sourceNodeId === sourceNode.id)) continue
      if (sourceNode.data.kind !== 'image' || (sourceNode.data.imagePurpose !== 'character' && sourceNode.data.imagePurpose !== 'prop')) continue
      const template = sourceNode.data.imagePurpose === 'character'
        ? createDirectorActor(initial.objects.filter((item) => item.kind === 'actor').length + 1)
        : createDirectorProp(initial.objects.filter((item) => item.kind === 'prop').length + 1)
      initial.objects.push({
        ...structuredClone(template),
        id: `source-${sourceNode.id}`,
        name: sourceNode.data.title,
        sourceNodeId: sourceNode.id,
        referenceImageUrl: sourceNode.data.mediaUrl,
        transform: {
          ...template.transform,
          position: resolveDirectorObjectSpawnPosition(initial.objects.filter((item) => item.kind === template.kind).length + 1, template.kind === 'actor' ? 'actor' : 'prop', template.shape)
        }
      })
    }
    return cloneDirectorScene(initial)
  })
  const [past, setPast] = useState<DirectorSceneData[]>([])
  const [future, setFuture] = useState<DirectorSceneData[]>([])
  const [selectedObjectIds, setSelectedObjectIds] = useState<string[]>(scene.objects[0]?.id ? [scene.objects[0].id] : [])
  const selectedObjectId = selectedObjectIds[0] ?? null
  const selectedObjectIdsRef = useRef(selectedObjectIds)
  const [sidebarTool, setSidebarTool] = useState<DirectorSidebarTool | null>(null)
  // Selecting in the viewport may update an open panel, but never opens a closed rail.
  const setActiveTool = useCallback((tool: DirectorSidebarTool) => setSidebarTool(current => current === null ? null : tool), [])
  const [panoramaUploading, setPanoramaUploading] = useState(false)
  const panoramaUploadInFlight = useRef(false)
  useEffect(() => () => { panoramaUploadInFlight.current = false }, [])
  const [characterInspectorTab, setCharacterInspectorTab] = useState<'props' | 'pose' | 'actions' | null>(null)
  const [activePoseJoint, setActivePoseJoint] = useState<string | null>(null)
  const [transformMode, setTransformMode] = useState<DirectorTransformMode>('translate')
  const leftCollapsed = sidebarTool === null
  const [rightCollapsed, setRightCollapsed] = useState(true)
  const [actionLibraryOpen, setActionLibraryOpen] = useState(false)
  const [timelineOpen, setTimelineOpen] = useState(false)
  const [recording, setRecording] = useState(false)
  const [recordingOpen, setRecordingOpen] = useState(false)
  const [openingFinish, setOpeningFinish] = useState(false)
  const finishLock = useRef(false)
  const [cameraSetupRequest, setCameraSetupRequest] = useState<DirectorCameraSetupRequest | null>(null)
  const [selectedPart, setSelectedPart] = useState<{ id: string; part: string } | null>(null)
  const [modelingView, setModelingView] = useState<string | null>(null)
  const [modelingPath, setModelingPath] = useState<{ id: string; position: Vec3 } | null>(null)
  const [shotProperties, setShotProperties] = useState(false)
  const [modelingReady, setModelingReady] = useState(!scene.modeling)
  const [localError, setLocalError] = useState('')
  const [localStatus, setLocalStatus] = useState('完整工程保存在本机')
  const [savedCloudScene, setSavedCloudScene] = useState<DirectorSceneData | null>(null)
  const [saving, setSaving] = useState(false)
  const [closing, setClosing] = useState(false)
  const closeInFlight = useRef(false)
  const [batchCapturing, setBatchCapturing] = useState(false)
  const [viewportKey, setViewportKey] = useState(0)
  const [viewportStatus, setViewportStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [viewportError, setViewportError] = useState('')
  const viewportRef = useRef<DirectorViewportHandle>(null)
  const projectInputRef = useRef<HTMLInputElement>(null)
  const sceneRef = useRef(scene)
  const transformTransactionRef = useRef<{ before: DirectorSceneData; changed: boolean } | null>(null)
  const monitorInspectorPreviewAtRef = useRef(0)
  const nodeMediaUrl = node?.data.mediaUrl
  const historyFingerprint = useRef('')
  const initialScene = useRef(scene)
  const [initialPublishedScene] = useState(() => {
    const published = storedScenes[scene.id]
    return published && stableCanvasJson(normalizeDirectorScene(published)) === stableCanvasJson(scene) ? scene : undefined
  })
  const publishedScene = useRef(initialPublishedScene)
  const localRevision = useRef(document.revision)
  const localFlight = useRef<Promise<unknown>>(Promise.resolve())
  const localSaved = useRef<DirectorSceneData | undefined>(document.recovered)
  const stageActive = useRef(true)
  useEffect(() => {
    stageActive.current = true
    return () => { stageActive.current = false }
  }, [])
  const isCurrentDirector = useCallback((sceneId: string) => {
    const workspace = useStudioUiStore.getState()
    return stageActive.current && workspace.scopeKey === scopeKey && workspace.directorOpenNodeId === nodeId
      && workspace.nodes.some(item => item.id === nodeId && item.data.directorSceneId === sceneId)
  }, [nodeId, scopeKey])
  const saveLocalDocument = useCallback((allowCloudCommit = false) => {
    const snapshot = sceneRef.current
    const current = () => isCurrentDirector(snapshot.id)
    const task = localFlight.current.catch(() => undefined).then(async () => {
      if (!current()) throw new Error('画布或目标节点已变化，未覆盖当前工程')
      if (localSaved.current === snapshot || publishedScene.current === snapshot) return snapshot
      setLocalStatus('正在保存本机完整工程…')
      let storedSnapshot = snapshot
      let recoveryWritten = true
      const writeRecovery = async () => {
        try {
          const saved = await saveThreePrevizLocal(document.key, storedSnapshot, localRevision.current, undefined, document.canvasBase)
          localRevision.current = saved.revision
        } catch (error) {
          // The recovery record belongs to another tab. Leave it intact and let the
          // authoritative scene version decide whether this cloud write is valid.
          if (!allowCloudCommit || !(error instanceof ThreePrevizLocalConflict)) throw error
          recoveryWritten = false
        }
      }
      if (snapshot.modeling) {
        const fingerprint = await threePrevizHistoryFingerprint(snapshot)
        const before = historyFingerprint.current || await threePrevizHistoryFingerprint(initialScene.current)
        if (fingerprint !== before) {
          const project = snapshot.modeling.project
          const revision = project.revision + 1
          const versionId = studioId('previz-version')
          storedSnapshot = { ...snapshot, modeling: { ...snapshot.modeling, project: { ...project, revision, versionId, updatedAt: new Date().toISOString(), versions: [...project.versions, { id: versionId, revision, summary: '统一3D预演编辑', createdAt: new Date().toISOString(), scene: projectThreePreviz(snapshot).scene }].slice(-20) } } }
        }
        if (!current()) throw new Error('画布或目标已变化，未覆盖当前工程')
        await writeRecovery()
        historyFingerprint.current = fingerprint
      } else {
        await writeRecovery()
      }
      if (recoveryWritten) localSaved.current = storedSnapshot
      if (current() && sceneRef.current === snapshot && storedSnapshot !== snapshot) {
        sceneRef.current = storedSnapshot
        setScene(storedSnapshot)
      }
      if (current()) {
        setLocalError('')
        setLocalStatus(recoveryWritten ? '完整工程已保存在本机' : '已保留另一窗口的本机恢复记录')
      }
      return storedSnapshot
    }).catch(error => {
      if (current()) { setLocalError(error instanceof Error ? error.message : '本机工程保存失败'); setLocalStatus('本机保存失败') }
      throw error
    })
    localFlight.current = task
    return task
  }, [document.canvasBase, document.key, isCurrentDirector])
  useEffect(() => {
    if (localError) return
    const timer = window.setTimeout(() => { void saveLocalDocument().catch(() => undefined) }, 600)
    return () => window.clearTimeout(timer)
  }, [scene, localError, saveLocalDocument])
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (localSaved.current !== sceneRef.current && publishedScene.current !== sceneRef.current) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])

  useEffect(() => () => clearDirectorCameraPreview(scene.id), [scene.id])
  useEffect(() => {
    const initial = sceneRef.current
    const playback = useDirectorPlaybackStore.getState()
    playback.reset(threePrevizDuration(initial))
    playback.setPlayhead(normalizeDirectorTimeline(initial.timeline).currentTime)
  }, [scene.id])
  const playbackDuration = threePrevizDuration(scene)
  const playbackEnd = threePrevizPlaybackEnd(scene)
  useEffect(() => {
    useDirectorPlaybackStore.getState().setRange(playbackDuration, playbackEnd)
  }, [scene.id, playbackDuration, playbackEnd])
  useEffect(() => {
    selectedObjectIdsRef.current = selectedObjectIds
  }, [selectedObjectIds])
  const selectObjects = useCallback((ids: string[]) => {
    setSelectedPart(null)
    setShotProperties(false)
    setModelingPath(null)
    setSelectedObjectIds(prioritizeUnlockedDirectorSelection(sceneRef.current.objects, ids))
  }, [])
  const selectObject = useCallback((id: string | null) => {
    setSelectedPart(null)
    setShotProperties(false)
    setModelingView(null)
    setModelingPath(null)
    const current = sceneRef.current
    const cameraSelected = isDirectorCameraSelection(current, id)
    if (id && cameraSelected) {
      setRightCollapsed(false)
      const next = {
        ...activateDirectorCamera(current, id),
        viewMode: 'director' as const,
        updatedAt: new Date().toISOString(),
      }
      sceneRef.current = next
      setScene(next)
    }
    if (id) setRightCollapsed(false)
    setSelectedObjectIds(id ? [id] : [])
  }, [])

  const changeScene = useCallback((next: DirectorSceneData) => {
    if (hasMissingThreePrevizBinding(next)) {
      message.warning('请先调整建模分镜或轨道，再移除对应的场景对象。')
      return sceneRef.current
    }
    const current = snapshotForHistory(sceneRef.current)
    setPast((items) => [...items, current].slice(-40))
    setFuture([])
    sceneRef.current = pruneDirectorPaths(next)
    setScene(sceneRef.current)
    return sceneRef.current
  }, [message])
  const replaceScene = useCallback((next: DirectorSceneData) => {
    sceneRef.current = next
    setScene(next)
    return next
  }, [])

  const phonePreviewFlush = useRef<number | undefined>(undefined)
  const getLiveScene = useCallback(() => sceneRef.current, [])
  const previewPhoneCamera = useCallback((next: DirectorSceneData, cameraId: string) => {
    if (closeInFlight.current) return
    const current = sceneRef.current
    const camera = resolveDirectorCamera(next, cameraId)
    if (current.id !== next.id || !camera || isDirectorCameraLocked(current, cameraId)) return
    const lens = resolveDirectorCameraLens(next, cameraId)
    // Merge just the live camera fields so a packet cannot replace another edit.
    sceneRef.current = updateDirectorCameraLens(updateDirectorCameraEntity(current, cameraId, camera), cameraId, lens)
    viewportRef.current?.previewCamera(cameraId, camera, lens)
    window.clearTimeout(phonePreviewFlush.current)
    phonePreviewFlush.current = window.setTimeout(() => {
      phonePreviewFlush.current = undefined
      setScene(sceneRef.current)
    }, 160)
  }, [])
  useEffect(() => () => window.clearTimeout(phonePreviewFlush.current), [scene.id])

  const uploadPanorama = async (file: File) => {
    if (panoramaUploadInFlight.current) return
    if (!canvasRuntime?.canEdit) {
      message.error('当前画布没有上传全景图的权限')
      return
    }
    if (!isDirectorPanoramaFile(file)) {
      message.error('请选择图片文件作为全景图')
      return
    }

    const { id, panoramaUrl } = sceneRef.current
    const current = () => {
      const state = useStudioUiStore.getState()
      return panoramaUploadInFlight.current && state.scopeKey === scopeKey && state.directorOpenNodeId === nodeId && sceneRef.current.id === id
    }
    panoramaUploadInFlight.current = true
    setPanoramaUploading(true)
    try {
      const bitmap = await createImageBitmap(file).catch(() => {
        throw new Error('浏览器无法读取这张全景图，请选择 JPG、PNG 或 WebP 图片')
      })
      bitmap.close()
      if (!current()) return
      const resource = await canvasRuntime.uploadResource(file, 'IMAGE')
      if (!current() || sceneRef.current.panoramaUrl !== panoramaUrl) return
      const url = canvasResourceDisplayUrl(resource)
      if (!url) throw new Error('全景图登记未返回可读取的地址')
      const latest = sceneRef.current
      changeScene({
        ...latest,
        panoramaUrl: url,
        panoramaHistory: [url, ...(latest.panoramaHistory ?? []).filter(item => item !== url && !item.startsWith('blob:'))].slice(0, 12),
        updatedAt: new Date().toISOString()
      })
    } catch (error) {
      if (current()) message.error(error instanceof Error ? error.message : '全景图上传失败')
    } finally {
      if (current()) setPanoramaUploading(false)
      panoramaUploadInFlight.current = false
    }
  }

  const applyRefreshedModelLinks = useCallback((next: DirectorSceneData) => {
    // Refreshing asset access links does not change authored content.
    if (publishedScene.current === sceneRef.current) publishedScene.current = next
    replaceScene(next)
  }, [replaceScene])
  const modelLinks = useDirectorManagedResourceRefresh({
    scene, nodeId, scopeKey, workspaceId: canvasRuntime?.project?.workspace.workspaceId,
    getScene: getLiveScene, applyScene: applyRefreshedModelLinks,
  })

  const beginTransform = useCallback(() => {
    transformTransactionRef.current = { before: snapshotForHistory(sceneRef.current), changed: false }
  }, [])
  const finishTransform = useCallback(() => {
    const transaction = transformTransactionRef.current
    transformTransactionRef.current = null
    if (!transaction?.changed) return
    setPast((items) => [...items, transaction.before].slice(-40))
    setFuture([])
  }, [])
  const applyTransformScene = useCallback((next: DirectorSceneData) => {
    const transaction = transformTransactionRef.current
    if (!transaction) {
      changeScene(next)
      return
    }
    transaction.changed = true
    replaceScene(next)
  }, [changeScene, replaceScene])
  const transformObject = useCallback((id: string, transform: DirectorTransform, companionIds: string[] = [], modelPartId?: string) => {
    const current = sceneRef.current
    const object = current.objects.find((item) => item.id === id)
    if (!object) {
      const project = projectThreePreviz(current)
      const model = project.scene.nodes.find(node => node.id === id && !node.directorBinding)
      if (!model || model.locked) return
      const frame = Math.min(project.scene.timeline.frameCount - 1, Math.round(useDirectorPlaybackStore.getState().playhead * 30))
      let graph = project.scene
      const localTransform = { ...model.localTransform }
      const part = modelPartId ? { ...model.assetOverrides?.[modelPartId] } : null
      for (const channel of ['position', 'rotation', 'scale'] as const) {
        if (graph.timeline.tracks.some(track => track.nodeId === id && track.objectId === modelPartId && track.channel === channel)) {
          graph = applyCommands(graph, [setKey(graph, id, channel, frame, transform[channel], modelPartId)])
        } else if (part) {
          part[channel === 'position' ? 'positionOffset' : channel === 'rotation' ? 'rotationOffset' : 'scaleMultiplier'] = transform[channel]
        } else localTransform[channel] = transform[channel]
      }
      const patch = part && modelPartId ? { assetOverrides: { ...model.assetOverrides, [modelPartId]: part } } : { localTransform }
      graph = applyCommands(graph, [{ type: 'node.update', id, patch }])
      applyTransformScene(applyThreePrevizProject(current, { ...project, scene: graph }))
      return
    }
    if (JSON.stringify(object.transform) === JSON.stringify(transform)) return
    const selectedIds = companionIds.includes(id) ? companionIds : [id, ...companionIds]
    applyTransformScene({
      ...current,
      objects: applyDirectorGroupedTransform(current.objects, selectedIds, id, transform),
      updatedAt: new Date().toISOString()
    })
  }, [applyTransformScene])
  const poseJointObject = useCallback((objectId: string, joint: string, value: number) => {
    const current = sceneRef.current
    const object = current.objects.find((item) => item.id === objectId)
    if (!object || object.pose[joint] === value) return
    applyTransformScene({
      ...current,
      objects: current.objects.map((item) => item.id === objectId
        ? { ...item, pose: { ...item.pose, [joint]: value } }
        : item),
      updatedAt: new Date().toISOString()
    })
  }, [applyTransformScene])
  const transformCameraEntity = useCallback((id: string, camera: DirectorCamera) => {
    const current = sceneRef.current
    const existing = id === 'main-camera'
      ? current.camera
      : current.cameraBookmarks?.find((item) => item.id === id)?.camera
    if (!existing || JSON.stringify(existing) === JSON.stringify(camera)) return
    const updated = updateDirectorCameraAimObjectId(updateDirectorCameraEntity(current, id, camera), id, undefined)
    applyTransformScene({ ...updated, updatedAt: new Date().toISOString() })
  }, [applyTransformScene])
  const previewMonitorCamera = useCallback((id: string, camera: DirectorCamera) => {
    const current = sceneRef.current
    const existing = resolveDirectorCamera(current, id)
    if (!existing) return
    const preview = { ...camera, position: [...existing.position] as DirectorCamera['position'] }
    const previewLens = syncDirectorLensToCameraFov(
      resolveDirectorCameraLens(current, id),
      preview,
      resolveDirectorAspectRatio(current.aspectRatio) ?? 16 / 9,
    )
    viewportRef.current?.previewCamera(id, preview, previewLens)
    const now = performance.now()
    if (now - monitorInspectorPreviewAtRef.current >= 1000 / 30) {
      monitorInspectorPreviewAtRef.current = now
      previewDirectorCamera(current.id, id, preview, previewLens)
    }
  }, [])
  const commitMonitorCamera = useCallback((id: string, camera: DirectorCamera) => {
    useDirectorPlaybackStore.getState().setEditingObjectId(null)
    const current = sceneRef.current
    const existing = resolveDirectorCamera(current, id)
    if (!existing) {
      clearDirectorCameraPreview(current.id, id)
      return
    }
    const committed = { ...camera, position: [...existing.position] as DirectorCamera['position'] }
    if (JSON.stringify(existing) === JSON.stringify(committed)) {
      clearDirectorCameraPreview(current.id, id)
      return
    }
    const lens = syncDirectorLensToCameraFov(
      resolveDirectorCameraLens(current, id),
      committed,
      resolveDirectorAspectRatio(current.aspectRatio) ?? 16 / 9,
    )
    let next = updateDirectorCameraEntity(current, id, committed)
    next = updateDirectorCameraLens(next, id, lens)
    next = updateDirectorCameraAimObjectId(next, id, undefined)
    changeScene({ ...next, updatedAt: new Date().toISOString() })
    clearDirectorCameraPreview(current.id, id)
  }, [changeScene])
  const selectMonitorCamera = useCallback((cameraId: string | null) => {
    selectObject(cameraId)
  }, [selectObject])

  const requestCameraSetup = useCallback((preset: string) => {
    const current = sceneRef.current
    const camera = preset === '当前视角'
      ? current.directorCamera ?? current.camera
      : DIRECTOR_CAMERA_PRESETS[preset] ?? current.directorCamera ?? current.camera
    setCameraSetupRequest({ key: Date.now(), preset, camera: structuredClone(camera) })
  }, [])

  const createCameraFromSetup = useCallback((result: DirectorCameraSetupResult) => {
    const current = sceneRef.current
    const bookmarks = current.cameraBookmarks ?? []
    const bookmark = createDirectorCameraBookmark(
      bookmarks.length + 1,
      result.name,
      result.camera,
      result.preset,
      result.lens,
    )
    bookmark.aimObjectId = result.aimObjectId
    const next = {
      ...current,
      viewMode: 'director' as const,
      activeCameraId: bookmark.id,
      cameraBookmarks: [...bookmarks, bookmark],
      updatedAt: new Date().toISOString(),
    }
    changeScene(next)
    selectObject(bookmark.id)
    setCameraSetupRequest(null)
    message.success(`已创建机位「${bookmark.name}」`)
  }, [changeScene, selectObject, message])

  const alignCameraToViewport = useCallback((cameraId: string) => {
    const current = sceneRef.current
    const directorCamera = current.directorCamera
    const camera = resolveDirectorCamera(current, cameraId)
    if (!directorCamera || !camera) return
    changeScene({
      ...updateDirectorCameraEntity(current, cameraId, {
        ...camera,
        position: [...directorCamera.position],
        target: [...directorCamera.target],
        roll: directorCamera.roll ?? 0,
      }),
      updatedAt: new Date().toISOString(),
    })
    message.success('机位已对准当前导演视口')
  }, [changeScene, message])

  const jumpToCamera = useCallback((cameraId: string) => {
    selectObject(cameraId)
    viewportRef.current?.focusOnCamera(cameraId)
  }, [selectObject])

  const undo = useCallback(() => {
    const previous = past.at(-1)
    if (!previous) return
    const current = snapshotForHistory(sceneRef.current)
    setFuture((items) => [current, ...items].slice(0, 40))
    setPast((items) => items.slice(0, -1))
    sceneRef.current = { ...snapshotForHistory(previous), revision: sceneRef.current.revision }
    setScene(sceneRef.current)
  }, [past])
  const redo = useCallback(() => {
    const next = future[0]
    if (!next) return
    const current = snapshotForHistory(sceneRef.current)
    setPast((items) => [...items, current].slice(-40))
    setFuture((items) => items.slice(1))
    sceneRef.current = { ...snapshotForHistory(next), revision: sceneRef.current.revision }
    setScene(sceneRef.current)
  }, [future])

  const publishSceneToCanvas = useCallback((snapshot: DirectorSceneData, previewImageUrl?: string, sceneOnly = false) => {
    setDirectorScene(snapshot)
    const cameraCount = listDirectorAuthoredCameras(snapshot).length
      + (snapshot.modeling?.project.scene.nodes.filter(node => node.kind === 'CAMERA').length ?? 0)
    if (!sceneOnly) updateNodeData(nodeId, {
      directorSceneId: snapshot.id,
      status: 'success',
      ...(previewImageUrl ? { mediaKind: 'image' as const, mediaUrl: previewImageUrl } : {}),
      resultSummary: `已保存 ${threePrevizObjectCount(snapshot)} 个对象、${cameraCount} 个机位、${snapshot.captures?.length ?? 0} 张截图`
    }, true)
  }, [nodeId, setDirectorScene, updateNodeData])

  const prepareSceneForSave = useCallback((previewImageUrl?: string) => {
    const current = sceneRef.current
    const draft = { ...current, ...(previewImageUrl ? { previewImageUrl } : {}), updatedAt: new Date().toISOString() }
    return replaceScene(persistentDirectorScene(draft))
  }, [replaceScene])

  const capturePreviewUrl = useCallback(() => {
    const fallback = sceneRef.current.previewImageUrl || nodeMediaUrl || undefined
    const viewport = viewportRef.current
    if (viewportStatus !== 'ready' || !viewport) return fallback
    try {
      const capturedUrl = resolveDirectorActiveCameraId(sceneRef.current) ? viewport.capture() : viewport.captureCurrentView()
      return capturedUrl || fallback
    } catch {
      // A lost viewport must not prevent the editable scene from being saved.
      return fallback
    }
  }, [nodeMediaUrl, viewportStatus])

  const saveCompleteScene = useCallback(async (previewImageUrl?: string, adoption?: Omit<CanvasSceneAdoption, 'sceneId'>) => {
    if (closeInFlight.current) throw new Error('正在返回画布')
    prepareSceneForSave(previewImageUrl)
    const snapshot = await saveLocalDocument(Boolean(canvasRuntime?.project))
    if (!isCurrentDirector(snapshot.id) || closeInFlight.current) throw new Error('已离开当前预演，未提交本次保存')
    publishSceneToCanvas(snapshot, previewImageUrl, Boolean(adoption))
    if (canvasRuntime?.project) {
      await canvasRuntime.saveNow(adoption && { ...adoption, sceneId: snapshot.id })
      if (!isCurrentDirector(snapshot.id) || sceneRef.current !== snapshot) return
      const authoritative = useStudioUiStore.getState().directorScenes[snapshot.id] ?? snapshot
      if (authoritative !== snapshot) {
        sceneRef.current = authoritative
        setScene(authoritative)
      }
      publishedScene.current = authoritative
      setSavedCloudScene(authoritative)
    } else {
      publishedScene.current = snapshot
    }
  }, [canvasRuntime, isCurrentDirector, prepareSceneForSave, publishSceneToCanvas, saveLocalDocument])

  const saveScene = useCallback(async (): Promise<boolean> => {
    if (saving || closeInFlight.current) return false
    setSaving(true)
    try {
      await saveCompleteScene(capturePreviewUrl())
      if (!isCurrentDirector(sceneRef.current.id) || closeInFlight.current) return false
      message.success(canvasRuntime?.project ? '3D预演场景已保存到当前画布' : '3D预演场景已保存到本机')
      return true
    } catch (error) {
      if (isCurrentDirector(sceneRef.current.id) && !closeInFlight.current) {
        message.error(error instanceof Error ? error.message : '导演场景保存失败')
      }
      return false
    } finally {
      setSaving(false)
    }
  }, [canvasRuntime, capturePreviewUrl, isCurrentDirector, saveCompleteScene, saving, message])

  const handleClose = useCallback(async () => {
    const sceneId = sceneRef.current.id
    if (recording || finishLock.current || closeInFlight.current || !isCurrentDirector(sceneId)) return
    if (publishedScene.current === sceneRef.current) {
      closeDirector()
      return
    }
    closeInFlight.current = true
    setClosing(true)
    try {
      const previewImageUrl = capturePreviewUrl()
      // A camera or a user edit can still arrive while IndexedDB is writing.
      // Only publish the exact draft that has been saved locally.
      let snapshot: DirectorSceneData
      do {
        prepareSceneForSave(previewImageUrl)
        snapshot = await saveLocalDocument()
        if (!isCurrentDirector(sceneId)) return
      } while (snapshot !== sceneRef.current)
      publishSceneToCanvas(snapshot, previewImageUrl)
      closeDirector()
    } catch (error) {
      if (isCurrentDirector(sceneId)) setLocalError(error instanceof Error ? error.message : '本机工程保存失败')
    } finally {
      closeInFlight.current = false
      if (isCurrentDirector(sceneId)) setClosing(false)
    }
  }, [capturePreviewUrl, closeDirector, isCurrentDirector, prepareSceneForSave, publishSceneToCanvas, recording, saveLocalDocument])
  const setMode = useCallback((mode: DirectorTransformMode) => {
    const cameraSelected = selectedObjectId === 'main-camera' || sceneRef.current.cameraBookmarks?.some((item) => item.id === selectedObjectId)
    const nextMode = cameraSelected && mode === 'scale' ? 'translate' : mode
    if (nextMode !== mode) message.warning('机位仅支持移动和旋转')
    setTransformMode(nextMode)
    viewportRef.current?.setTransformMode(nextMode)
  }, [selectedObjectId, message])

  useEffect(() => {
    const cameraSelected = selectedObjectId === 'main-camera' || scene.cameraBookmarks?.some((item) => item.id === selectedObjectId)
    if (cameraSelected && transformMode === 'scale') setMode('translate')
  }, [scene.cameraBookmarks, selectedObjectId, setMode, transformMode])

  useEffect(() => {
    if (viewportStatus !== 'loading') return
    const timeout = window.setTimeout(() => {
      setViewportError('三维视口初始化超时。请检查浏览器硬件加速后重试，当前场景内容不会丢失。')
      setViewportStatus('error')
    }, 12_000)
    return () => window.clearTimeout(timeout)
  }, [viewportKey, viewportStatus])

  const deleteEntities = useCallback((ids: string[]) => {
    const current = sceneRef.current
    const project = projectThreePreviz(current)
    const entityId = (node: PrevizNode) => node.directorBinding?.id ?? node.id
    const targets = project.scene.nodes.filter(node => ids.includes(entityId(node)) && (node.kind !== 'LIGHT' || !node.directorBinding))
    if (!targets.length) return
    const removedIds = new Set(targets.map(node => node.id))
    const byId = new Map(project.scene.nodes.map(node => [node.id, node]))
    for (const node of project.scene.nodes) {
      if (!node.locked) continue
      let affected: PrevizNode | undefined = node
      while (affected) {
        if (removedIds.has(affected.id)) {
          message.warning('请先解锁要删除的对象、机位及其子对象')
          return
        }
        affected = affected.parentId ? byId.get(affected.parentId) : undefined
      }
    }
    try {
      const graph = applyCommands(project.scene, targets.map(node => ({ type: 'node.remove', id: node.id })))
      const remaining = new Set(graph.nodes.map(entityId))
      const selection = selectedObjectIdsRef.current.filter(id => remaining.has(id))
      const next = applyThreePrevizProject(current, {
        ...project,
        scene: graph,
        annotations: project.annotations.map(annotation => ({
          ...annotation,
          nodeIds: annotation.nodeIds.filter(id => graph.nodes.some(node => node.id === id))
        }))
      })
      const deletedPreview = modelingView && modelingView !== '@sequence'
        && !graph.nodes.some(node => node.id === modelingView)
      if (changeScene(deletedPreview ? { ...next, viewMode: 'director' } : next) === current) return
      if (deletedPreview) setModelingView(null)
      if (selection.length !== selectedObjectIdsRef.current.length) {
        selectedObjectIdsRef.current = selection
        selectObjects(selection)
      }
    } catch (error) {
      message.warning(error instanceof Error ? error.message : '无法删除场景对象')
    }
  }, [changeScene, modelingView, selectObjects, message])

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || recording || recordingOpen || cameraSetupRequest || sceneRef.current.viewportPrefs?.pathDrawMode) return
      if (event.target instanceof HTMLElement && event.target.closest('input, textarea, [contenteditable="true"], .ant-modal, .ant-tour, [role="menu"]')) return
      if (event.key === 'Escape') {
        void handleClose()
        return
      }
      const command = event.ctrlKey || event.metaKey
      if (command && event.key.toLowerCase() === 'z') {
        event.preventDefault()
        if (event.shiftKey) redo()
        else undo()
        return
      }
      const key = event.key.toLowerCase()
      if (key === ' ') { event.preventDefault(); const p = useDirectorPlaybackStore.getState(); p.setPlaying(!p.isPlaying); return }
      if (key === 'i') { event.preventDefault(); try { changeScene(insertDirectorWaypoint(sceneRef.current, selectedObjectIdsRef.current[0] ?? null)) } catch (e) { message.warning((e as Error).message) }; return }
      if (key === 'v') setMode('translate')
      if (key === 'r') setMode('rotate')
      if (key === 'f') setMode('scale')
      if (key === 'h') viewportRef.current?.resetView()
      if (key === 't') changeScene({ ...sceneRef.current, directorCamera: { position: [0, 12, 0.01], target: [0, 0, 0], fov: 48 }, viewMode: 'director' })
      if (key === 'y') changeScene({ ...sceneRef.current, directorCamera: structuredClone(DIRECTOR_CAMERA_PRESETS['正面全景']), viewMode: 'director' })
      if (key === 'x') changeScene({ ...sceneRef.current, gridSnap: !sceneRef.current.gridSnap })
      if ((event.key === 'Delete' || event.key === 'Backspace') && selectedObjectIdsRef.current.length) {
        event.preventDefault()
        deleteEntities(selectedObjectIdsRef.current)
      }
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [cameraSetupRequest, changeScene, deleteEntities, handleClose, redo, setMode, undo, recording, recordingOpen, message])

  const handleViewportSelect = useCallback((id: string | null, options?: DirectorViewportSelectOptions) => {
    const current = sceneRef.current
    if (isDirectorCameraSelection(current, id)) {
      selectObject(id)
      setActiveTool('camera')
      return
    }
    const object = id ? current.objects.find((item) => item.id === id) : undefined
    const crowdGroupId = object ? resolveDirectorCrowdGroupId(object) : undefined
    const nextIds = resolveDirectorViewportSelection({
      currentIds: selectedObjectIdsRef.current,
      nextId: id,
      additive: options?.additive,
      selectCrowdGroup: options?.selectCrowdGroup,
      crowdMemberIds: crowdGroupId ? listDirectorCrowdMemberIds(current.objects, crowdGroupId) : undefined
    })
    selectObjects(nextIds)
    setActiveTool('scene')
    if (object && current.viewMode !== 'director') {
      replaceScene({ ...current, viewMode: 'director', updatedAt: new Date().toISOString() })
    }
  }, [replaceScene, selectObject, selectObjects, setActiveTool])

  const modelingSession = useThreePrevizSession({ scene, change: changeScene, replace: replaceScene, undo, redo, canUndo: !!past.length, canRedo: !!future.length, save: adoption => saveCompleteScene(undefined, adoption), cloud: Boolean(canvasRuntime?.project) })
  const sequenceCamera = useDirectorPlaybackStore(state => modelingView === '@sequence' ? shotAt(modelingSession.ref.current.scene, Math.round(state.playhead * 30)).cameraId : undefined)
  const selectAuthoredObject = (id: string, part?: string) => {
    const binding = modelingSession.ref.current.scene.directorLayer?.bindings[id]
    selectObject(binding?.id ?? (id || null))
    if (!binding && id) {
      setActiveTool('scene')
      setRightCollapsed(false)
      if (part) setSelectedPart({ id, part })
      if (sceneRef.current.viewMode === 'camera') replaceScene({ ...sceneRef.current, viewMode: 'director' })
    }
  }
  const previewAuthoredCamera = (id: string) => {
    if (id === PREVIZ_DIRECTOR_VIEW_ID) {
      setModelingView(null)
      replaceScene({ ...sceneRef.current, viewMode: 'director' })
      return
    }
    const binding = modelingSession.ref.current.scene.directorLayer?.bindings[id]
    setModelingView(binding?.kind === 'camera' ? null : id)
    const current = sceneRef.current
    replaceScene({ ...(binding?.kind === 'camera' ? activateDirectorCamera(current, binding.id) : current), viewMode: 'camera' })
  }
  const snapshotAuthoredCamera = (id?: string): Pick<PrevizNode, 'localTransform' | 'camera'> => {
    const modelCamera = id ? viewportRef.current?.getRuntime()?.modelingCameras.get(id) : undefined
    const authored = id ? modelingSession.ref.current.scene.nodes.find(item => item.id === id) : undefined
    const source = sceneRef.current.directorCamera ?? sceneRef.current.camera
    if (authored?.camera) return { localTransform: structuredClone(authored.localTransform), camera: structuredClone(authored.camera) }
    if (modelCamera instanceof PerspectiveCamera || modelCamera instanceof OrthographicCamera) {
      const position = modelCamera.getWorldPosition(new Vector3())
      return {
        localTransform: { position: position.toArray(), rotation: [0, 0, 0], scale: [1, 1, 1] },
        camera: {
          target: position.clone().add(modelCamera.getWorldDirection(new Vector3())).toArray(),
          fov: modelCamera instanceof PerspectiveCamera ? modelCamera.fov : 50,
          near: modelCamera.near, far: modelCamera.far,
          ...(modelCamera instanceof OrthographicCamera ? { projection: 'orthographic', orthoHeight: modelCamera.top - modelCamera.bottom } as const : {}),
        },
      }
    }
    return {
      localTransform: { position: [...source.position], rotation: [0, 0, 0], scale: [1, 1, 1] },
      camera: { target: [...source.target], fov: source.fov, near: 0.1, far: 200, roll: (source.roll ?? 0) * Math.PI / 180 },
    }
  }
  const importModelingProject = (project: PrevizProject) => changeScene({ ...sceneRef.current, modeling: { schemaVersion: 1, project } })
  const openSceneSettings = () => {
    selectObject(null)
    setRightCollapsed(false)
    if (sceneRef.current.viewMode === 'camera') replaceScene({ ...sceneRef.current, viewMode: 'director' })
  }
  const commitModelingPath = (points: Vec3[]) => {
    if (!modelingPath) return
    const graph = modelingSession.ref.current.scene
    const node = graph.nodes.find(item => item.id === modelingPath.id)
    if (!node || node.locked || node.parentId || node.camera?.followPosition) { setModelingPath(null); return }
    const frameCount = graph.timeline.frameCount
    const track = { id: uid(), nodeId: node.id, channel: 'position' as const, interpolation: 'linear' as const,
      keys: positionPathKeys(points, modelingPath.position[1], frameCount) }
    modelingSession.execute([{ type: 'timeline.replace', shots: graph.shots, timeline: {
      ...graph.timeline, tracks: [...graph.timeline.tracks.filter(item => item.nodeId !== node.id || item.objectId || item.channel !== 'position'), track],
    } }])
    setModelingPath(null)
    setTimelineOpen(true)
    useDirectorPlaybackStore.getState().setPlayhead(0)
  }

  if (!node) return null

  const capture = () => {
    const mediaUrl = viewportRef.current?.capture()
    if (!mediaUrl) {
      message.error('截图失败，请等待三维视口加载完成')
      return
    }
    const current = sceneRef.current
    const captureItem: DirectorCapture = {
      id: studioId('capture'),
      name: `机位截图 ${String((current.captures?.length ?? 0) + 1).padStart(2, '0')}`,
      mediaUrl,
      camera: structuredClone(resolveDirectorActiveCamera(current)),
      createdAt: new Date().toISOString()
    }
    const next = { ...current, captures: [...(current.captures ?? []), captureItem], previewImageUrl: mediaUrl, updatedAt: new Date().toISOString() }
    changeScene(next)
    setDirectorScene(next)
    updateNodeData(nodeId, { directorSceneId: next.id, status: 'success', mediaKind: 'image', mediaUrl })
    message.success('当前机位已截取，可在右侧机位面板发送到画布')
  }
  const sendCaptureToCanvas = (mediaUrl: string) => {
    addDirectorCapture(nodeId, mediaUrl)
    message.success('截图已发送到画布并建立引用连线')
  }
  const captureBatch = (cameraIds?: string[], sendToCanvas = true) => {
    if (batchCapturing) return
    if (viewportStatus !== 'ready') {
      message.error('请等待三维视口加载完成后再批量出图')
      return
    }
    const { targets, omitted } = resolveDirectorBatchShotPlan(sceneRef.current, cameraIds)
    if (!targets.length) {
      message.warning('请先添加或勾选要出图的机位')
      return
    }
    setBatchCapturing(true)
    try {
      const captured = targets.flatMap((target, index) => {
        const mediaUrl = viewportRef.current?.captureFromCamera(target.camera) ?? ''
        return mediaUrl ? [createDirectorShotCapture(target, mediaUrl, index + 1)] : []
      })
      if (!captured.length) {
        message.error('批量出图失败，请重试或先截取当前机位')
        return
      }
      const next = appendDirectorShotCaptures(sceneRef.current, captured)
      changeScene(next)
      setDirectorScene(next)
      updateNodeData(nodeId, {
        directorSceneId: next.id,
        status: 'success',
        mediaKind: 'image',
        mediaUrl: next.previewImageUrl,
        resultSummary: `已按 ${captured.length} 个机位出图`
      })
      if (sendToCanvas) {
        addDirectorCaptures(nodeId, captured.map((item) => ({
          mediaUrl: item.mediaUrl,
          title: resolveDirectorShotCanvasTitle(item.name)
        })))
        message.success(omitted
          ? `已按 ${captured.length} 个机位出图并发送到画布，另有 ${omitted} 个可见机位超出上限未出图`
          : `已按 ${captured.length} 个机位出图并发送到画布`)
        return
      }
      message.success(omitted
        ? `已按 ${captured.length} 个机位出图，另有 ${omitted} 个可见机位超出上限未出图`
        : `已按 ${captured.length} 个机位出图，可在截图面板发送到画布`)
    } finally {
      setBatchCapturing(false)
    }
  }
  const importProject = async (file?: File) => {
    if (!file) return
    try {
      const origin = sceneRef.current
      const originalScope = useStudioUiStore.getState().scopeKey
      const parsed = await readThreePrevizProject(file, origin)
      if (sceneRef.current !== origin || useStudioUiStore.getState().scopeKey !== originalScope) throw new Error('读取期间场景或画布已变化，未覆盖当前编辑')
      const imported = { ...parsed, id: sceneRef.current.id, updatedAt: new Date().toISOString() }
      changeScene(imported)
      selectObject(imported.objects[0]?.id ?? 'main-camera')
      message.success('3D预演工程已导入，保存场景后会写入当前工作区')
    } catch (error: unknown) {
      message.error(error instanceof Error ? error.message : '3D预演工程导入失败')
    }
  }
  const exportProject = () => {
    try {
      downloadThreePrevizProject(sceneRef.current)
      message.success('3D预演工程已下载')
    } catch (error: unknown) {
      message.error(error instanceof Error ? error.message : '3D预演工程导出失败')
    }
  }
  const openFinish = async () => {
    if (finishLock.current || closeInFlight.current || recording || saving) return
    const source = sceneRef.current
    if (!findDirectorPrevizVideo(nodeId, source.id)) {
      setRecordingOpen(true)
      return
    }
    finishLock.current = true
    setOpeningFinish(true)
    try {
      const previewImageUrl = capturePreviewUrl()
      prepareSceneForSave(previewImageUrl)
      const snapshot = await saveLocalDocument()
      if (!isCurrentDirector(snapshot.id)) return
      publishSceneToCanvas(snapshot, previewImageUrl)
      const { hasReferenceImage } = openDirectorFinishComposer(nodeId, snapshot)
      closeDirector()
      message.success(hasReferenceImage
        ? '已建立成片草稿，确认参考素材、模型和报价后生成'
        : '已建立成片草稿，可补充美术参考图，再确认模型和报价')
    } catch (error) {
      message.warning(error instanceof Error ? error.message : '无法打开成片草稿')
    } finally {
      finishLock.current = false
      setOpeningFinish(false)
    }
  }

  const nativeSelection = scene.modeling?.project.scene.nodes.find(item => item.id === selectedObjectId)
  const authoringSelection = modelingSession.project.scene.nodes.find(item => (item.directorBinding?.id ?? item.id) === selectedObjectId)?.id ?? ''
  const viewMode = scene.viewMode ?? 'director'
  const hasCamera = Boolean(resolveDirectorActiveCameraId(scene))
  const selectedCameraId = isDirectorCameraSelection(scene, selectedObjectId) ? selectedObjectId : null
  const frameRatio = resolveDirectorAspectRatio(scene.aspectRatio)
  const poseEditMode = characterInspectorTab === 'pose'
    && Boolean(selectedObjectId && scene.objects.some((item) => item.id === selectedObjectId && item.kind === 'actor'))

  return (
    <DirectorModelingTools owner={document.owner} session={modelingSession}
      selection={{ id: authoringSelection, objectId: selectedPart?.id === authoringSelection ? selectedPart.part : undefined }}
      onSelect={selectAuthoredObject} capture={() => viewportRef.current?.captureCurrentView() ?? ''}
      getCameraSnapshot={snapshotAuthoredCamera} onPreviewCamera={previewAuthoredCamera}
      onOpenInspector={openSceneSettings} onImport={importModelingProject} onDeleteObject={id => deleteEntities([id])}>
    {tools => <div className="director-stage" role="dialog" aria-modal="true" aria-label="3D预演">
      <header className="director-topbar">
        <div className="director-topbar__identity">
          <Button type="text" icon={<CloseOutlined />} loading={closing} onClick={() => void handleClose()} aria-label="保存并退出3D预演" />
          <strong>3D预演</strong>
          <Input value={scene.name} aria-label="导演场景名称" onChange={(event) => replaceScene({ ...scene, name: event.target.value })} />
        </div>
        <Segmented
          value={viewMode}
          options={[{ value: 'director', label: '导演视角' }, { value: 'camera', label: '机位视角', disabled: !hasCamera }]}
          onChange={(value) => {
            const nextMode = value as DirectorViewMode
            setModelingView(null)
            if (nextMode === 'camera' && !isDirectorCameraSelection(sceneRef.current, selectedObjectId)) {
              selectObject(resolveDirectorActiveCameraId(sceneRef.current) || null)
            }
            changeScene({ ...sceneRef.current, viewMode: nextMode })
          }}
          aria-label="视角切换"
        />
        <div className="director-topbar__actions">
          <span className="director-save-status" role="status">
            {closing ? '正在保存本机工程…' : canvasRuntime?.project && saving ? '正在保存到画布…' : savedCloudScene === scene || (canvasRuntime?.project && publishedScene.current === scene) ? '完整工程已保存到画布' : localStatus}
          </span>
          <Tooltip title="撤销 Ctrl/⌘ + Z"><Button type="text" icon={<UndoOutlined />} disabled={!past.length} onClick={undo} aria-label="撤销" /></Tooltip>
          <Tooltip title="重做 Ctrl/⌘ + Shift + Z"><Button type="text" icon={<RedoOutlined />} disabled={!future.length} onClick={redo} aria-label="重做" /></Tooltip>
          <Button icon={<SaveOutlined />} aria-label="保存3D预演" data-previz-step="save" loading={saving} disabled={closing} onClick={() => void saveScene()}>保存</Button>
          <Dropdown
            menu={{
              items: [
                {
                  key: 'import',
                  icon: <ImportOutlined />,
                  label: '导入工程',
                  onClick: () => projectInputRef.current?.click(),
                },
                { key: 'legacy', label: '旧工程与回收站', onClick: tools.openProjects },
                { key: 'versions', label: '工程版本', onClick: tools.openHistory },
                { key: 'formats', label: '导出图片、视频或模型', onClick: tools.openExport },
                {
                  key: 'export',
                  icon: <ExportOutlined />,
                  label: '导出工程',
                  onClick: () => exportProject(),
                },
              ],
            }}
            trigger={['click']}
          >
            <Button type="text" icon={<EllipsisOutlined />} aria-label="更多工程操作" />
          </Dropdown>
          <input ref={projectInputRef} type="file" accept="application/json,.json" hidden onChange={(event) => { void importProject(event.target.files?.[0]); event.target.value = '' }} />
        </div>
      </header>
      {localError ? <div className="director-unified-error" role="alert"><span>{localError}</span><Button onClick={() => void saveLocalDocument().catch(() => undefined)}>重试保存</Button><Button onClick={exportProject}>导出完整工程</Button></div> : null}
      {tools.status}
      <DirectorPrevizToolbar scene={scene} onChange={changeScene} recording={recording} openingFinish={openingFinish}
        tutorialReady={modelLinks.status === 'ready' && viewportStatus === 'ready'}
        onOpenRecording={() => setRecordingOpen(true)} onOpenFinish={() => void openFinish()} onOpenTimeline={() => setTimelineOpen(true)} />
      <DirectorActionLibraryDialog
        open={actionLibraryOpen}
        scene={scene}
        actorId={selectedObjectId}
        onChange={changeScene}
        onClose={() => setActionLibraryOpen(false)}
        onApplied={id => {
          setSelectedObjectIds([id])
          setActionLibraryOpen(false)
          setTimelineOpen(true)
        }}
      />
      <main className={`director-stage__body${timelineOpen ? ' has-timeline' : ''}${leftCollapsed ? ' is-left-collapsed' : ''}${rightCollapsed ? ' is-right-collapsed' : ''}`}>
        <DirectorSidebar
          scenePrimitives={tools.primitives}
          modelingObjects={tools.objects}
          modelingCameras={tools.cameras}
          assetTools={<div className="director-modeling-controls">{tools.assets}</div>}
          onOpenActions={() => setActionLibraryOpen(true)}
          onOpenSceneSettings={openSceneSettings}
          nodeId={nodeId}
          scene={scene}
          activeTool={sidebarTool}
          selectedObjectIds={selectedObjectIds}
          onToolChange={setSidebarTool}
          onSelectObject={selectObject}
          onSelectObjects={selectObjects}
          onDeleteObjects={deleteEntities}
          onChange={changeScene}
          onUploadPanorama={uploadPanorama}
          panoramaUploading={panoramaUploading}
          onCreateCameraRequest={requestCameraSetup}
        />
        <div className="director-stage__viewport-wrap" data-recording={recording || undefined}>
          {modelLinks.status === 'ready' ? <DirectorViewport
            key={viewportKey}
            ref={viewportRef}
            sceneData={scene}
            selectedObjectId={selectedObjectId}
            selectedObjectIds={selectedObjectIds}
            viewMode={viewMode}
            modelingCameraId={sequenceCamera ?? modelingView ?? undefined}
            modelingView={modelingView}
            selectedModelPart={selectedPart?.id === selectedObjectId ? selectedPart.part : undefined}
            poseEditMode={poseEditMode}
            activePoseJoint={activePoseJoint}
            onSelect={(id, options) => {
              if (id && sceneRef.current.modeling?.project.scene.nodes.some(item => item.id === id)) selectAuthoredObject(id, options?.modelPartId)
              else handleViewportSelect(id, options)
            }}
            onCameraChange={(camera) => replaceScene({
              ...updateDirectorCameraEntity(sceneRef.current, resolveDirectorActiveCameraId(sceneRef.current), camera),
              updatedAt: new Date().toISOString()
            })}
            onDirectorCameraChange={(directorCamera) => replaceScene({ ...sceneRef.current, directorCamera, updatedAt: new Date().toISOString() })}
            onObjectTransform={transformObject}
            onPoseJointChange={poseJointObject}
            onActivePoseJointChange={setActivePoseJoint}
            onCameraEntityTransform={transformCameraEntity}
            onTransformStart={beginTransform}
            onTransformEnd={finishTransform}
            onReady={() => setViewportStatus('ready')}
            onError={(error) => {
              setViewportError(error.message || '当前设备无法初始化 WebGL 三维视口')
              setViewportStatus('error')
            }}
          /> : null}
          {scene.modeling ? <DirectorModelingRuntime viewport={viewportRef} scene={scene} ready={modelLinks.status === 'ready' && viewportStatus === 'ready'} onReady={setModelingReady} onError={error => { setViewportError(error.message); setViewportStatus('error') }} /> : null}
          <DirectorSceneryRuntime selectedId={selectedObjectId} viewport={viewportRef} scene={scene} ready={modelLinks.status === 'ready' && viewportStatus === 'ready'} />
          <DirectorMonitorPip
            viewport={viewportRef}
            scene={scene}
            getLiveScene={getLiveScene}
            nodeId={nodeId}
            ready={modelLinks.status === 'ready' && viewportStatus === 'ready' && (!scene.modeling || modelingReady)}
            selectedCameraId={selectedCameraId}
            recordingOpen={recordingOpen}
            captureBusy={recording}
            onRecordingOpenChange={setRecordingOpen}
            onRecording={setRecording}
            motionDuration={playbackEnd}
            modelingView={modelingView}
            onCameraSelect={selectMonitorCamera}
            onModelingCameraSelect={id => { if (id) previewAuthoredCamera(id); else setModelingView(null) }}
            onCameraPreview={previewMonitorCamera}
            onCameraCommit={commitMonitorCamera}
          />
          {viewMode === 'director' && selectedCameraId ? (
            <DirectorCameraViewportHud scene={scene} cameraId={selectedCameraId} />
          ) : null}
          {scene.viewportPrefs?.showNameplates && <DirectorNameplates viewport={viewportRef} scene={scene} />}
          {scene.viewportPrefs?.pathDrawMode && <DirectorPathDrawOverlay key={`${scene.viewportPrefs.pathDrawMode}:${scene.viewportPrefs.pathDrawPlane ?? 'xz'}:${selectedObjectId}:${scene.viewportPrefs.pathEditClipId ?? ''}`} scene={scene} targetId={selectedObjectId} viewport={viewportRef} onChange={changeScene} />}
          {modelingPath && <DirectorPathDrawOverlay key={modelingPath.id} scene={scene} targetId={modelingPath.id} viewport={viewportRef} onChange={changeScene}
            modeling={{ position: modelingPath.position, onCommit: commitModelingPath, onCancel: () => setModelingPath(null) }} />}
          {viewMode === 'camera' && frameRatio ? <div className="director-camera-frame" style={{ aspectRatio: frameRatio }} aria-label={`${scene.aspectRatio ?? '16:9'} 机位画幅`}><span>{scene.aspectRatio ?? '16:9'}</span></div> : null}
          <div className="director-view-compass" aria-hidden="true"><i /><i /><i /><i /></div>
          <Button className="director-reset-view" size="small" onClick={() => viewportRef.current?.resetView()}>重置视角</Button>
          <DirectorBottomBar
            scene={scene}
            renderDock={tools.assistant}
            onAnnotate={tools.openAnnotation}
            onFocus={() => {
              if (selectedCameraId) viewportRef.current?.focusOnCamera(selectedCameraId)
              else if (selectedObjectId) viewportRef.current?.focusOnObject(selectedObjectId)
              else viewportRef.current?.resetView()
            }}
            extraTimelineRows={tools.timeline}
            extraTimelineRowCount={modelingSession.project.scene.timeline.tracks.length + 1}
            onEditShots={() => { selectObject(null); setShotProperties(true); setRightCollapsed(false) }}
            onPreviewShots={() => { setModelingView('@sequence'); replaceScene({ ...sceneRef.current, viewMode: 'camera' }) }}
            modelingPathAction={nativeSelection && !selectedPart && !nativeSelection.locked && !nativeSelection.parentId && !nativeSelection.camera?.followPosition ? () => {
              setModelingView(null)
              useDirectorPlaybackStore.getState().setPlaying(false)
              replaceScene({ ...sceneRef.current, viewMode: 'director' })
              setModelingPath({ id: nativeSelection.id, position: nativeSelection.localTransform.position })
            } : undefined}
            selectedObjectId={selectedObjectId}
            onSelectObject={selectObject}
            transformMode={transformMode}
            timelineOpen={timelineOpen}
            batchCapturing={batchCapturing}
            onTransformModeChange={setMode}
            onTimelineOpenChange={setTimelineOpen}
            onCapture={capture}
            onBatchCapture={() => captureBatch(undefined, true)}
            onSceneChange={changeScene}
            onCommitPlayhead={time => {
              const current = sceneRef.current
              const timeline = current.timeline ?? normalizeDirectorTimeline()
              if (Math.abs(time - timeline.currentTime) > 0.001) replaceScene({ ...current, timeline: { ...timeline, currentTime: time } })
            }}
            captureBusy={recording}
            onCaptureBusyChange={setRecording}
            getViewportCanvas={() => viewportRef.current?.getCanvas() ?? null}
            beginViewportRecording={(mode) => {
              if (!viewportRef.current) throw new Error('三维视口尚未准备好')
              return viewportRef.current.beginVideoCapture(mode)
            }}
            renderViewportRecordingFrame={(time) => {
              if (!viewportRef.current) throw new Error('录制视口尚未准备好')
              return viewportRef.current.renderVideoCaptureFrame(time)
            }}
          />
        </div>
        <DirectorInspector
          authoringDetails={nativeSelection || shotProperties ? <div className="director-inspector__scroll director-modeling-controls">{tools.inspector}</div> : undefined}
          authoringTitle={nativeSelection ? nativeSelection.name : shotProperties ? '分镜' : undefined}
          exposureControl={tools.exposure}
          viewport={viewportRef}
          scene={scene}
          beforeCameraPair={saveCompleteScene}
          workspaceId={useStudioUiStore.getState().scopeKey}
          selectedObjectId={selectedObjectId}
          selectedObjectIds={selectedObjectIds}
          viewMode={viewMode}
          batchCapturing={batchCapturing}
          onSelect={selectObject}
          onChange={changeScene}
          onPreviewChange={previewPhoneCamera}
          getLiveScene={getLiveScene}
          onSendCapture={sendCaptureToCanvas}
          onBatchCapture={captureBatch}
          onFocusObject={(id) => viewportRef.current?.focusOnObject(id)}
          onAlignCameraToViewport={alignCameraToViewport}
          onJumpToCamera={jumpToCamera}
          onCharacterTabChange={setCharacterInspectorTab}
          onOpenTimeline={() => setTimelineOpen(true)}
          activePoseJoint={activePoseJoint}
          onActivePoseJointChange={setActivePoseJoint}
          collapsed={rightCollapsed}
          onCollapsedChange={setRightCollapsed}
        />
      </main>
      {cameraSetupRequest ? (
        <DirectorCameraSetupModal
          key={cameraSetupRequest.key}
          request={cameraSetupRequest}
          scene={scene}
          viewport={viewportRef}
          onCancel={() => setCameraSetupRequest(null)}
          onCreate={createCameraFromSetup}
        />
      ) : null}
      {modelLinks.status === 'loading' || (modelLinks.status === 'ready' && viewportStatus === 'loading') ? <DirectorStageLoading /> : null}
      {modelLinks.status === 'error' ? (
        <DirectorStageRuntimeError
          title="场景模型读取失败"
          retryLabel="重新读取模型"
          reason={`${modelLinks.error}。当前场景编辑已保留。`}
          onClose={() => void handleClose()}
          onRetry={() => {
            setViewportStatus('loading')
            void modelLinks.refresh()
          }}
        />
      ) : viewportStatus === 'error' ? (
        <DirectorStageRuntimeError
          reason={viewportError}
          onClose={() => void handleClose()}
          onRetry={() => {
            setViewportError('')
            setViewportStatus('loading')
            setViewportKey((value) => value + 1)
            void modelLinks.refresh()
          }}
        />
      ) : null}
    </div>}
    </DirectorModelingTools>
  )
}
