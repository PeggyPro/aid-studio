'use client'

import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent, DragEvent as ReactDragEvent } from 'react'
import { message } from 'antd'
import { ApartmentOutlined, CloudUploadOutlined, CloseOutlined, FileTextOutlined, LoadingOutlined } from '@ant-design/icons'
import { useScriptLimits } from '~/hooks/useScriptLimits'
import { plainPureTextCharCount } from '~/utils/htmlPlain'
import { useCreationStore } from '~/stores/creation'
import {
  creationStepAdvance,
  userScriptSplitConfirm,
  userScriptSplitPreview
} from '~/utils/businessApi'
import type { ScriptSplitConfirmVO, ScriptSplitPreviewVO } from '~/types/business-api'
import { assertScriptPlainTextFile, validateScriptUploadFile } from '~/utils/scriptFileUpload'
import { noDataIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import { fetchUserProjectDetailOnce } from '~/utils/userProjectDetailOnce'
import { assetUrl } from '~/utils/assetUrl'
import '~/app/create/series-script-upload/series-script-upload.css'

const emptyImageIconUrl = assetUrl(emptyImageIconRaw)

const MAX_SCRIPT_BYTES = 10 * 1024 * 1024

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb >= 10 ? kb.toFixed(0) : kb.toFixed(2)}KB`
  const mb = kb / 1024
  return `${mb.toFixed(2)}MB`
}

async function readFileAsText(file: File): Promise<string> {
  const bytes = await file.arrayBuffer()
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    throw new Error('无法读取剧本，请将文件另存为 UTF-8 编码的 TXT。')
  }
}

interface SeriesScriptImportProps {
  projectId: number
  onImported: (result: ScriptSplitConfirmVO) => void
  onSkip?: () => void
  onBusyChange?: (busy: boolean) => void
}

/** 整篇剧本导入：原上传页与剧集管理共用预览、确认和并发控制。父级按项目设置 key。 */
export function SeriesScriptImport({ projectId, onImported, onSkip, onBusyChange }: SeriesScriptImportProps) {
  const operationRef = useRef(0)
  const busyRef = useRef(false)
  const mountedRef = useRef(false)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      operationRef.current += 1
      busyRef.current = false
    }
  }, [])

  const limits = useScriptLimits()
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [pendingFile, setPendingFile] = useState<File | null>(null)
  const [displayFileName, setDisplayFileName] = useState('')
  const [isDragging, setIsDragging] = useState(false)
  const [parsing, setParsing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [episodeKeyword, setEpisodeKeyword] = useState('第一集')
  const [scriptText, setScriptText] = useState('')
  const [previewData, setPreviewData] = useState<ScriptSplitPreviewVO | null>(null)
  const dragDepthRef = useRef(0)

  useEffect(() => {
    onBusyChange?.(parsing || confirming)
    return () => onBusyChange?.(false)
  }, [parsing, confirming, onBusyChange])

  const hasFile = !!pendingFile

  const fileSizeLabel = pendingFile ? formatFileSize(pendingFile.size) : ''

  function openPicker() {
    fileInputRef.current?.click()
  }

  function assignFile(file: File | undefined) {
    if (!file || !limits || busyRef.current) return
    const formatError = validateScriptUploadFile(file)
    if (formatError) {
      message.warning(formatError)
      return
    }
    if (file.size > MAX_SCRIPT_BYTES) {
      message.warning('文件过大，请选择较小的 txt 文件')
      return
    }
    setPendingFile(file)
    setDisplayFileName(file.name)
    setPreviewData(null)
    setScriptText('')
  }

  function removeFile() {
    if (busyRef.current) return
    setPendingFile(null)
    setDisplayFileName('')
    setPreviewData(null)
    setScriptText('')
  }

  function backToFile() {
    if (busyRef.current) return
    setPreviewData(null)
    setScriptText('')
  }

  function onFileChange(e: ChangeEvent<HTMLInputElement>) {
    const input = e.target
    assignFile(input.files?.[0])
    input.value = ''
  }

  function onDragEnter(e: ReactDragEvent<HTMLDivElement>) {
    e.preventDefault()
    if (hasFile || previewData) return
    dragDepthRef.current += 1
    setIsDragging(true)
  }

  function onDragOver(e: ReactDragEvent<HTMLDivElement>) {
    e.preventDefault()
    if (hasFile || previewData) return
    setIsDragging(true)
  }

  function onDragLeave(e: ReactDragEvent<HTMLDivElement>) {
    e.preventDefault()
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
    if (dragDepthRef.current === 0) setIsDragging(false)
  }

  function onDrop(e: ReactDragEvent<HTMLDivElement>) {
    e.preventDefault()
    dragDepthRef.current = 0
    setIsDragging(false)
    if (hasFile || previewData) return
    assignFile(e.dataTransfer?.files?.[0])
  }

  async function onPreviewSplit() {
    const file = pendingFile
    if (!file || busyRef.current) return
    if (!limits) {
      message.warning('尚未取得剧本字数限制，请刷新页面后重试')
      return
    }
    busyRef.current = true
    const operation = ++operationRef.current
    const isCurrent = () => mountedRef.current && operationRef.current === operation
    const keyword = episodeKeyword.trim() || '第一集'
    setParsing(true)
    try {
      const formatError = validateScriptUploadFile(file)
      if (formatError) {
        message.warning(formatError)
        return
      }
      try {
        await assertScriptPlainTextFile(file)
      } catch (e: unknown) {
        const err = e as { message?: string }
        if (isCurrent()) message.error(err?.message || '内容非文本')
        return
      }

      if (!isCurrent()) return
      const detail = await fetchUserProjectDetailOnce(projectId)
      if (!isCurrent()) return
      if (detail.projectType !== 'series') {
        message.warning('仅剧集项目支持整篇分集导入')
        return
      }

      const text = await readFileAsText(file)
      if (!isCurrent()) return
      if (!text.trim()) {
        message.error('未能从文档中解析出文字，请检查文件内容')
        return
      }

      if (plainPureTextCharCount(text.replace(/<\/?br\s*\/?>/gi, '')) > limits.seriesMaxChars) {
        message.error('整篇剧本超过 ' + limits.seriesMaxChars.toLocaleString('zh-CN') + ' 字，请删减后重试')
        return
      }
      const preview = await userScriptSplitPreview({
        projectId,
        scriptText: text,
        episodeKeyword: keyword
      })
      if (!isCurrent()) return
      if (!preview.totalEpisodes || !preview.items.length) {
        message.error('未识别分集词')
        return
      }

      const oversized = preview.items.find((item) => (item.charCount ?? 0) > limits.episodeMaxChars)
      if (oversized) {
        message.error('第 ' + oversized.episodeNo + ' 集超过单集字数上限')
        return
      }
      setScriptText(text)
      setEpisodeKeyword(keyword)
      setPreviewData(preview)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      if (isCurrent()) message.error(err?.msg || err?.message || '分集预览失败，请稍后重试')
    } finally {
      if (isCurrent()) {
        busyRef.current = false
        setParsing(false)
      }
    }
  }

  async function onConfirmSplit() {
    if (!previewData || busyRef.current) return
    const text = scriptText
    if (!text.trim()) {
      message.error('剧本文本丢失，请重新解析')
      return
    }

    busyRef.current = true
    const operation = ++operationRef.current
    const isCurrent = () => mountedRef.current && operationRef.current === operation
    setConfirming(true)
    try {
      const keyword = episodeKeyword.trim() || '第一集'
      const result = await userScriptSplitConfirm({
        projectId,
        scriptText: text,
        episodeKeyword: keyword,
        firstEpisodeNo: previewData.firstEpisodeNo
      })

      if (!isCurrent()) return
      // 入库已经成功，先清除可重复提交的预览；后续步骤更新或列表刷新不应再次导入。
      setPreviewData(null)
      setPendingFile(null)
      setScriptText('')
      const first = result.episodes?.[0]
      const firstEpisodeId = Number(first?.episodeId)
      if (useCreationStore.getState().currentProjectId === projectId && Number.isFinite(firstEpisodeId) && firstEpisodeId > 0) {
        useCreationStore
          .getState()
          .setCurrentProjectContext({ projectId, episodeId: firstEpisodeId })
      }

      try {
        await creationStepAdvance({
          projectId,
          episodeId:
            Number.isFinite(firstEpisodeId) && firstEpisodeId > 0 ? firstEpisodeId : undefined,
          completedStep: 2
        })
      } catch {
        /* 服务端未配置步骤时仍可进入分集列表 */
      }

      if (!isCurrent()) return
      useCreationStore.getState().setSeriesFlowEnteredStoryScript(true)
      message.success(`已创建 ${result.totalEpisodes} 集`)
      onImported(result)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      if (isCurrent()) message.error(err?.msg || err?.message || '分集入库失败，请稍后重试')
    } finally {
      if (isCurrent()) {
        busyRef.current = false
        setConfirming(false)
      }
    }
  }

  return (
    <div
      className={`series-script-upload${isDragging && !hasFile && !previewData ? ' is-dragging' : ''}`}
      onDragEnter={onDragEnter}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
    >
      {/* 未选择文件：空状态 */}
      {!hasFile && !previewData ? (
        <div className="series-script-upload__panel" data-onboarding="series-upload-area">
          <div className="series-script-upload__icon" aria-hidden="true">
            <img src={emptyImageIconUrl} alt="" className="empty-image-icon empty-image-icon--xl" />
          </div>
          <p className="series-script-upload__hint">
            {limits ? 'UTF-8 TXT，整篇最多 ' + limits.seriesMaxChars.toLocaleString('zh-CN') + ' 字，单集最多 ' + limits.episodeMaxChars.toLocaleString('zh-CN') + ' 字。' : '正在获取剧本字数上限，请稍候。'}
          </p>
          <input
            ref={fileInputRef}
            type="file"
            className="series-script-upload__input"
            accept=".txt,text/plain"
            onChange={onFileChange}
          />
          <button type="button" className="series-script-upload__btn" disabled={confirming} onClick={openPicker}>
            <CloudUploadOutlined className="series-script-upload__btn-ico" />
            <span className="series-script-upload__btn-text">{confirming ? '正在更新剧集…' : '上传剧本'}</span>
          </button>
          {onSkip ? <button type="button" className="series-script-upload__skip" disabled={confirming} onClick={onSkip}>
            暂不上传，直接进入剧集列表
          </button> : null}
        </div>
      ) : !previewData ? (
        /* 已选文件：待解析 */
        <div className="series-script-upload__done">
          <header className="series-script-upload__intro">
            <span className="series-script-upload__step">第 1 步 / 共 2 步</span>
            <h2>识别剧集</h2>
            <p>按剧本中的集数标题拆分章节，确认预览后再导入。</p>
          </header>
          <div className="series-script-upload__file-card">
            <div className="series-script-upload__word-badge" aria-hidden="true">
              <FileTextOutlined />
            </div>
            <div className="series-script-upload__file-info">
              <div className="series-script-upload__file-name">{displayFileName}</div>
              <div className="series-script-upload__file-meta">TXT · {fileSizeLabel}</div>
            </div>
            <button
              type="button"
              className="series-script-upload__close"
              aria-label="移除文件"
              disabled={parsing || confirming}
              onClick={removeFile}
            >
              <CloseOutlined />
            </button>
          </div>

          <label className="series-script-upload__keyword">
            <span className="series-script-upload__keyword-label">分集词样例</span>
            <input
              disabled={parsing || confirming}
              value={episodeKeyword}
              onChange={(e) => setEpisodeKeyword(e.target.value)}
              type="text"
              className="series-script-upload__keyword-input"
              placeholder="默认：第一集"
              aria-describedby="series-script-keyword-hint"
              maxLength={50}
            />
            <span id="series-script-keyword-hint" className="series-script-upload__guide">填写剧本里的标题示例，例如「第一集」或「第 1 话」。</span>
          </label>

          <button
            type="button"
            className="series-script-upload__parse"
            data-onboarding="series-parse-result"
            disabled={parsing || confirming}
            onClick={() => void onPreviewSplit()}
          >
            {parsing ? <LoadingOutlined spin aria-hidden="true" /> : <ApartmentOutlined aria-hidden="true" />}
            {parsing ? (
              <span className="series-script-upload__parse-loading">解析中…</span>
            ) : (
              <span>解析剧本</span>
            )}
          </button>
        </div>
      ) : (
        /* 分集预览确认 */
        <div className="series-script-upload__preview">
          <div className="series-script-upload__preview-head">
            <span className="series-script-upload__step">第 2 步 / 共 2 步</span>
            <h2 className="series-script-upload__preview-title">分集预览</h2>
            <p className="series-script-upload__preview-meta">
              共 {previewData.totalEpisodes} 集
              {previewData.totalCharCount != null ? <> · {previewData.totalCharCount} 字</> : null}
              {previewData.episodeKeyword ? <> · 分集词「{previewData.episodeKeyword}」</> : null}
            </p>
          </div>

          <ul className="series-script-upload__preview-list">
            {previewData.items.map((item) => (
              <li key={item.episodeNo} className="series-script-upload__preview-item">
                <div className="series-script-upload__preview-item-title">
                  第{item.episodeNo}集 · {item.title}
                </div>
                <div className="series-script-upload__preview-item-desc">
                  {item.description || '暂无描述'}
                  {item.charCount != null ? <span>（{item.charCount} 字）</span> : null}
                </div>
              </li>
            ))}
          </ul>

          <div className="series-script-upload__preview-actions">
            <button
              type="button"
              className="series-script-upload__preview-back"
              disabled={confirming}
              onClick={backToFile}
            >
              重新选择
            </button>
            <button
              type="button"
              className="series-script-upload__parse"
              disabled={confirming}
              onClick={() => void onConfirmSplit()}
            >
              {confirming ? (
                <span className="series-script-upload__parse-loading">入库中…</span>
              ) : (
                <span>确认分集并入库</span>
              )}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
