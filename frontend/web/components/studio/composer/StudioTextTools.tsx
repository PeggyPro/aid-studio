'use client'

import { useEffect, useRef, useState } from 'react'
import { FileTextOutlined } from '@ant-design/icons'
import { StudioChromeIcon } from '../theme/StudioChromeIcon'
import { message } from 'antd'
import type { CanvasProjectWorkspaceRuntime } from '@/hooks/studio/useCanvasProjectWorkspace'
import { apiErrorMessage } from '@/utils/apiError'
import { StudioCanvasTooltip } from '@/components/studio/canvas/StudioCanvasTooltip'

/** 文本节点仅提供文件导入；生成会话由任务记录管理，不在输入栏选择或续聊。 */
export function StudioTextTools({ nodeId, runtime }: {
  nodeId: string
  runtime: CanvasProjectWorkspaceRuntime
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const importFlight = useRef(false)
  const requestEpoch = useRef({ value: 0 })
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    const epoch = requestEpoch.current
    return () => { mounted.current = false; epoch.value++ }
  }, [nodeId, runtime.project?.workspace.workspaceId])
  const [importing, setImporting] = useState(false)
  return (
    <div className="studio-text-tools">
      <input
        ref={inputRef}
        className="studio-text-tools__file"
        type="file"
        accept=".txt,.md,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        aria-label="导入文本文件"
        onChange={async (event) => {
          const file = event.currentTarget.files?.[0]
          event.currentTarget.value = ''
          if (!file || importFlight.current || !runtime.canMutate) return
          importFlight.current = true
          const importEpoch = requestEpoch.current.value
          setImporting(true)
          try {
            await runtime.importNodeText(nodeId, file)
            if (mounted.current && requestEpoch.current.value === importEpoch) message.success('文本已导入并保存')
          } catch (error) {
            if (mounted.current && requestEpoch.current.value === importEpoch) message.error(apiErrorMessage(error, '文本导入失败'))
          } finally {
            importFlight.current = false
            if (mounted.current) setImporting(false)
          }
        }}
      />
      <StudioCanvasTooltip title={!runtime.canMutate ? '当前画布不可编辑，请检查成员权限或协作连接' : importing ? '正在导入文本' : '导入文本文件'}><button type="button" className="studio-text-tools__action" disabled={!runtime.canMutate || importing}
        onClick={() => inputRef.current?.click()}>
        <StudioChromeIcon name="importText" fallback={<FileTextOutlined />} /> {importing ? '导入中' : '导入文本'}
      </button></StudioCanvasTooltip>
    </div>
  )
}
