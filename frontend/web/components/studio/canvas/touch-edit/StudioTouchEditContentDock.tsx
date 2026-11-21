'use client'

import { UnorderedListOutlined } from '@ant-design/icons'
import { useStudioUiStore } from '@/stores/studioUi'
import { canSubmitTouchEdit } from '@/utils/studio/touchEditSessionModel'
import { studioRegionThumbStyle } from '@/utils/studio/studioRegionThumbStyle'
import { useStudioTouchEditActions } from '@/hooks/studio/useStudioTouchEditActions'
import { StudioCreditSendButton } from '@/components/studio/composer/StudioCreditSendButton'
import { StudioComposerChip } from '@/components/studio/composer/StudioComposerChip'
import { StudioCanvasDropdownPopover } from '@/components/studio/canvas/StudioCanvasDropdownMenu'
import { useCanvasImageEdit } from '@/hooks/studio/useCanvasImageEdit'
import { useCanvasImageQuote } from '@/hooks/studio/useCanvasImageQuote'
import { useCanvasProjectRuntime } from '@/components/studio/free/CanvasProjectRuntimeContext'
import type { StudioCanvasNode, StudioTouchEditRegion } from '@/types/studio'
import { useEffect, useState } from 'react'
import { message } from 'antd'
import { studioCanvasQuoteCredits } from '@/utils/studio/studioCanvasQuoteDisplay'

export function StudioTouchEditContentDock({ nodeId }: { nodeId: string }) {
  const session = useStudioUiStore((state) => state.touchEditSession)
  const patchTouchEditSession = useStudioUiStore((state) => state.patchTouchEditSession)
  const closeTouchEditSession = useStudioUiStore((state) => state.closeTouchEditSession)
  const mediaUrl = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId)?.data.mediaUrl || '')
  const node = useStudioUiStore((state) => state.nodes.find((item) => item.id === nodeId))
  const { generate, busy } = useStudioTouchEditActions(nodeId)

  if (!session || session.nodeId !== nodeId) return null

  const edits = session.edits
  const canSubmit = canSubmitTouchEdit(edits) && !busy

  return (
    <div className="studio-touch-edit-content" role="region" aria-label="编辑内容">
      <div className="studio-touch-edit-content__head">
        <span>
          <UnorderedListOutlined style={{ marginRight: 6 }} />
          编辑内容
        </span>
        <span>{edits.length > 0 ? `${edits.length} 处` : '待添加'}</span>
      </div>
      {edits.length > 0 ? (
        <div className="studio-touch-edit-content__list">
          {edits.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`studio-touch-edit-content__row${session.activeRegionId === item.id ? ' is-active' : ''}`}
              onClick={() => patchTouchEditSession({ activeRegionId: item.id })}
            >
              <div
                className="studio-touch-edit-region-menu__thumb"
                style={studioRegionThumbStyle(mediaUrl, item.rect, 28)}
              />
              <span className="studio-touch-edit-content__tag">
                {item.detectLabel?.trim() || '选区'}
              </span>
              <span className="studio-touch-edit-content__text">{item.instruction}</span>
            </button>
          ))}
        </div>
      ) : null}
      <div className="studio-touch-edit-content__actions">
        <button
          type="button"
          className="studio-touch-edit-content__cancel"
          onClick={() => closeTouchEditSession()}
        >
          取消
        </button>
        {node ? <TouchEditGenerate node={node} edits={edits} canSubmit={canSubmit} busy={busy} onDemoGenerate={generate} /> : null}
      </div>
    </div>
  )
}

function TouchEditGenerate({ node, edits, canSubmit, busy, onDemoGenerate }: {
  node: StudioCanvasNode
  edits: StudioTouchEditRegion[]
  canSubmit: boolean
  busy: boolean
  onDemoGenerate: () => Promise<boolean>
}) {
  const canvasEdit = useCanvasImageEdit(node)
  const ensureSourceResource = canvasEdit.ensureSourceResource
  const runtime = useCanvasProjectRuntime()
  const [modelCode, setModelCode] = useState('')
  const [modelOpen, setModelOpen] = useState(false)
  const [sourceResource, setSourceResource] = useState<{ url: string; id: string } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const selection = canvasEdit.select('canvas.image.touch_edit', 'image_edit', modelCode)
  const model = selection.model
  const sourceResourceId = sourceResource && sourceResource.url === node.data.mediaUrl ? sourceResource.id : ''
  const sourceVersion = runtime?.getNodeVersion(node.id) ?? undefined
  useEffect(() => {
    if (!canvasEdit.active || !node.data.mediaUrl) return
    let active = true
    void ensureSourceResource().then((resource) => {
      if (active) setSourceResource({ url: node.data.mediaUrl!, id: String(resource.resourceId) })
    }).catch(() => { if (active) setSourceResource(null) })
    return () => { active = false }
  }, [canvasEdit.active, ensureSourceResource, node.data.mediaUrl])
  const selections = edits.slice(0, 8).map((item) => ({
    x: item.rect.x, y: item.rect.y, width: item.rect.w, height: item.rect.h,
    instruction: item.instruction.trim()
  }))
  const quoteRequest = canvasEdit.active && canvasEdit.project && model && sourceResourceId && sourceVersion && canSubmit
    && edits.length <= 8 && !edits.some((item) => item.kind === 'move') ? {
    modelName: model.modelCode, funcCode: 'canvas.image.touch_edit', capabilityCode: 'image_edit',
    projectId: String(canvasEdit.project.projectId), workspaceId: String(canvasEdit.project.workspace.workspaceId),
    clientRequestId: 'touch-edit-quote', prompt: '', expectedImageCount: 1,
    imageEdit: { operation: 'TOUCH_EDIT' as const, sourceResourceId, sourceObjectId: node.id,
      sourceExpectedVersion: sourceVersion, selections }
  } : null
  const quote = useCanvasImageQuote(quoteRequest)
  const quotedCredits = studioCanvasQuoteCredits(quote.quote)
  const reason = canvasEdit.active
    ? canvasEdit.disabledReason || (edits.some((item) => item.kind === 'move')
      ? '当前画布图片接口仅支持区域修改，暂不支持移动元素'
      : edits.length > 8 ? '一次最多编辑 8 个区域' : !model ? canvasEdit.error || '暂无可用的元素编辑模型' : !sourceResourceId || !sourceVersion ? '正在保存原图素材' : !edits.length ? '请先添加编辑内容' : quote.error || (quote.quote?.matched === false ? '当前参数没有可用计费规则' : !quote.quote ? '正在获取报价' : ''))
    : ''
  const submit = async () => {
    if (!canSubmit || submitting || reason) return
    const submittedSession = useStudioUiStore.getState().touchEditSession
    setSubmitting(true)
    try {
      const ok = canvasEdit.active && model
        ? await canvasEdit.submit({ modelCode: model.modelCode, funcCode: 'canvas.image.touch_edit', capabilityCode: 'image_edit',
          imageEdit: { operation: 'TOUCH_EDIT', sourceObjectId: node.id,
            sourceExpectedVersion: sourceVersion, selections },
          expectedImageCount: 1, label: '元素编辑', purpose: node.data.imagePurpose ?? 'generic', prompt: '' })
        : await onDemoGenerate()
      if (ok && canvasEdit.active) useStudioUiStore.getState().closeTouchEditSession(submittedSession)
    } catch (error: unknown) {
      message.error(error instanceof Error ? error.message : '元素编辑提交失败')
    } finally { setSubmitting(false) }
  }
  return <>
    {canvasEdit.active && <StudioCanvasDropdownPopover open={modelOpen} onOpenChange={setModelOpen} placement="top"
      ariaLabel={'\u5143\u7d20\u7f16\u8f91\u6a21\u578b'} activeKey={model?.modelCode || ''}
      items={selection.models.map((item) => ({ key: item.modelCode, label: item.modelName }))}
      onSelect={setModelCode}
      trigger={<StudioComposerChip label={model?.modelName || '\u9009\u62e9\u6a21\u578b'} active={modelOpen} />} />}
    <StudioCreditSendButton
      credits={canvasEdit.active ? quotedCredits ?? 0 : 16}
      isFree={canvasEdit.active && quote.quote?.isFree === true}
      authoritative={canvasEdit.active && quotedCredits != null}
      pendingQuote={canvasEdit.active && quotedCredits == null}
      estimateLoading={canvasEdit.active && quote.loading}
      estimateError={quote.error || (canvasEdit.active && quote.quote?.matched === false ? '当前参数没有可用计费规则' : '')}
      quoteStatus={canvasEdit.active ? quote.status : undefined}

      hasContent={canSubmit}
      mode="agent"
      loading={busy || submitting}
      disabled={!canSubmit || busy || submitting || Boolean(reason)}
      disabledReason={reason || ''}
      className="studio-touch-edit-content__generate"
      onSend={() => { void submit() }}
    />
  </>
}
