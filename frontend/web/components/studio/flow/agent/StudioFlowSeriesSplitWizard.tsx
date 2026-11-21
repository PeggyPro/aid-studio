'use client'

import { useRef, useState } from 'react'
import { Alert, Button, Input, Modal, message } from 'antd'
import { CloudUploadOutlined } from '@ant-design/icons'
import type { ScriptSplitPreviewVO } from '@/types/business-api'
import { useCreationStore } from '@/stores/creation'
import { useScriptLimits } from '@/hooks/useScriptLimits'
import { plainPureTextCharCount } from '@/utils/htmlPlain'
import {
  userScriptSplitConfirm,
  userScriptSplitPreview
} from '@/utils/businessApi'
import { assertScriptPlainTextFile, validateScriptUploadFile } from '@/utils/scriptFileUpload'

const MAX_SCRIPT_BYTES = 10 * 1024 * 1024

export function StudioFlowSeriesSplitWizard({
  open,
  projectId,
  onOpenChange,
  onCompleted,
  markFlowEntered = true
}: {
  open: boolean
  projectId: number
  onOpenChange: (open: boolean) => void
  onCompleted?: () => void
  markFlowEntered?: boolean
}) {
  const limits = useScriptLimits(open)
  const busy = useRef(false)
  const [validationError, setValidationError] = useState('')
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [pendingFile, setPendingFile] = useState<File | null>(null)
  const [episodeKeyword, setEpisodeKeyword] = useState('第一集')
  const [scriptText, setScriptText] = useState('')
  const [previewData, setPreviewData] = useState<ScriptSplitPreviewVO | null>(null)
  const [parsing, setParsing] = useState(false)
  const [confirming, setConfirming] = useState(false)

  const reset = () => {
    setValidationError('')
    setPendingFile(null)
    setEpisodeKeyword('第一集')
    setScriptText('')
    setPreviewData(null)
    setParsing(false)
    setConfirming(false)
  }

  const assignFile = (file: File | undefined) => {
    if (!file) return
    const formatError = validateScriptUploadFile(file)
    if (formatError) {
      message.warning(formatError)
      return
    }
    if (file.size > MAX_SCRIPT_BYTES) {
      message.warning('文件过大，请选择较小的 txt 文件')
      return
    }
    setValidationError('')
    setPendingFile(file)
    setPreviewData(null)
    setScriptText('')
  }

  const onPreviewSplit = async () => {
    const file = pendingFile
    if (!file || !limits || busy.current) return
    busy.current = true
    setValidationError('')
    setParsing(true)
    try {
      await assertScriptPlainTextFile(file)
      const bytes = await file.arrayBuffer()
      let text: string
      try {
        text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
      } catch {
        setValidationError('无法读取剧本，请将文件另存为 UTF-8 编码的 TXT。')
        return
      }
      if (!text.trim()) {
        message.error('未能从文档中解析出文字，请检查文件内容')
        return
      }
      if (plainPureTextCharCount(text.replace(/<\/?br\s*\/?>/gi, '')) > limits.seriesMaxChars) {
        setValidationError('整篇剧本超过 ' + limits.seriesMaxChars.toLocaleString('zh-CN') + ' 字，请删减后重试。')
        return
      }
      const keyword = episodeKeyword.trim() || '第一集'
      const preview = await userScriptSplitPreview({
        projectId,
        scriptText: text,
        episodeKeyword: keyword
      })
      if (!preview.totalEpisodes || !preview.items.length) {
        message.error('未识别分集词')
        return
      }
      const oversized = preview.items.find((item) => (item.charCount ?? 0) > limits.episodeMaxChars)
      if (oversized) {
        setValidationError('第 ' + oversized.episodeNo + ' 集超过单集字数上限，请删减后重试。')
        return
      }
      setScriptText(text)
      setEpisodeKeyword(String(preview.episodeKeyword || keyword))
      setPreviewData(preview)
    } catch (error) {
      setValidationError((error as { msg?: string; message?: string })?.msg || (error as Error)?.message || '分集预览失败，请稍后重试')
    } finally {
      busy.current = false
      setParsing(false)
    }
  }

  const onConfirmSplit = async () => {
    if (!previewData || busy.current) return
    const text = scriptText
    if (!text.trim()) {
      message.error('剧本文本丢失，请重新解析')
      return
    }
    busy.current = true
    setValidationError('')
    setConfirming(true)
    try {
      const keyword = episodeKeyword.trim() || '第一集'
      const result = await userScriptSplitConfirm({
        projectId,
        scriptText: text,
        episodeKeyword: keyword,
        firstEpisodeNo: previewData.firstEpisodeNo
      })
      if (markFlowEntered) useCreationStore.getState().setSeriesFlowEnteredStoryScript(true)
      message.success('已创建 ' + result.totalEpisodes + ' 集，正文已保存到各集的剧本创作')
      onOpenChange(false)
      reset()
      onCompleted?.()
    } catch (error) {
      setValidationError((error as { msg?: string; message?: string })?.msg || (error as Error)?.message || '分集确认失败，请稍后重试')
    } finally {
      busy.current = false
      setConfirming(false)
    }
  }

  return (
    <Modal
      open={open}
      title="上传剧本并拆集"
      className="create-flow-modal"
      okText={previewData ? '确认创建分集' : '预览拆分结果'}
      cancelText="取消"
      confirmLoading={previewData ? confirming : parsing}
      okButtonProps={{ disabled: !pendingFile || !limits }}
      cancelButtonProps={{ disabled: parsing || confirming }}
      onCancel={() => {
        if (busy.current) return
        onOpenChange(false)
        reset()
      }}
      onOk={() => void (previewData ? onConfirmSplit() : onPreviewSplit())}
    >
      <div className="studio-flow-series-split">
        <p>上传 UTF-8 TXT 剧本。标题前的前言会保留在第一集正文，已有剧集时从下一集追加。每集标题独占一行，例如「第一集：初遇」，下一行开始写正文。</p>
        <p>{limits ? '整篇最多 ' + limits.seriesMaxChars.toLocaleString('zh-CN') + ' 字，单集最多 ' + limits.episodeMaxChars.toLocaleString('zh-CN') + ' 字。' : '正在获取剧本字数上限。若未显示，请关闭后重试。'}</p>
        <input
          ref={fileInputRef}
          type="file"
          accept=".txt,text/plain"
          hidden
          onChange={(event) => {
            assignFile(event.target.files?.[0])
            event.target.value = ''
          }}
        />
        <Button disabled={parsing || confirming} icon={<CloudUploadOutlined />} onClick={() => fileInputRef.current?.click()}>
          {pendingFile ? pendingFile.name : '选择剧本文件'}
        </Button>
        <label className="studio-flow-series-split__field">
          <span>分集关键词</span>
          <Input
            value={episodeKeyword}
            placeholder="第一集"
            disabled={parsing || confirming}
            onChange={(event) => {
              setEpisodeKeyword(event.target.value)
              setPreviewData(null)
              setValidationError('')
            }}
          />
        </label>
        {validationError ? <Alert type="error" showIcon title={validationError} /> : null}
        {previewData ? (
          <ul className="studio-flow-series-split__preview" style={{ maxHeight: '38vh', overflowY: 'auto' }}>
            {previewData.items.map((item) => (
              <li key={item.episodeNo}>
                <strong>第 {item.episodeNo} 集</strong>
                <span>{item.title || '未命名'} · {item.charCount ?? 0} 字</span>
                {item.description ? <p>{item.description}</p> : null}
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </Modal>
  )
}
