'use client'

import { useEffect, useRef, useState } from 'react'
import { Button } from 'antd'
import { PaperClipOutlined } from '@ant-design/icons'
import type { AssistantContextRef } from '~/types/assistant-api'
import type { AssistantArtifactChunk, AssistantSourceRevision } from '~/types/assistant-artifact'
import type { AssistantSourceImportSummary, AssistantSourceImportDetail, AssistantSourcePending, AssistantSourceImportCommand, AssistantSourceAdvanceRequest } from '~/types/assistant-source-import'
import { sourceDocumentContext } from '~/types/assistant-source-import'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { assistantCommandId, assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'
import { assistantCommandWasRejected } from '~/utils/assistant/assistantRequestCoordinator'
import { sourceImportRevisionForReading } from '~/utils/assistant/assistantSourceImports'
import { mergeAssistantSourceContent } from '~/utils/assistant/assistantArtifacts'
import { assistantSha256 } from '~/utils/assistant/assistantSha256'

const labels: Record<string,string> = { UPLOADED:'已上传', DISCOVERING:'正在识别分片', PARTIAL:'等待继续解析', PARSING:'正在解析', FINALIZING:'正在保存原文', FINALIZED:'已保存完整原文', FAILED:'解析失败', CANCELLED:'已取消解析' }
const errors: Record<string,string> = { SOURCE_PDF_OCR_REQUIRED:'此 PDF 没有文本层，请先识别文字后重新导入。', SOURCE_PDF_ENCRYPTED:'请先解除 PDF 的密码保护。', SOURCE_PARSE_TIMEOUT:'解析超时，可用原操作核实或重试失败分片。', SOURCE_PARSER_BUSY:'解析服务正忙，请稍后核实原操作。' }
const screenplayErrors: Record<string, string> = {
  FLOW_SCRIPT_MULTIPLE_EPISODES: '请上传单集剧本。整部剧本请到剧集管理导入。',
  FLOW_SCRIPT_EPISODE_TOO_LONG: '单集剧本超过字数上限（最多 10,000 字），请缩短后上传。',
  FLOW_SCRIPT_SOURCE_TOO_LARGE: '参考剧本文本过长，请拆分后上传。',
  FLOW_SCRIPT_SOURCE_EMPTY: '上传的单集剧本没有正文。',
}
type Props = { store: AssistantSessionStore; sessionId: string; actorId: string; disabled: boolean } & (
  | { direct: true; onAttach: (ref: AssistantContextRef) => void; onBusyChange: (busy: boolean) => void }
  | { direct?: false; active?: boolean; selectionActivation?: number; onSelection: (refs: AssistantContextRef[]) => void }
)
type Journal = { version: 1; pending: AssistantSourcePending | null; cancellation?: AssistantSourceAdvanceRequest | null; selected: string[] }

function validPending(value: unknown, sessionId: string): value is AssistantSourcePending {
  if (!value || typeof value !== 'object') return false
  const item = value as AssistantSourcePending
  if (item.kind === 'UPLOAD') return item.sessionId === sessionId && typeof item.clientRequestId === 'string'
    && typeof item.mediaType === 'string' && item.mediaType.length <= 100
    && typeof item.fileName === 'string' && /^[a-f0-9]{64}$/.test(item.fileHash) && Number.isSafeInteger(item.byteCount) && item.byteCount > 0 && item.byteCount <= 10 * 1024 * 1024
  return ['ADVANCE','CANCEL'].includes(item.kind) && item.body?.sessionId === sessionId && /^[1-9][0-9]*$/.test(item.body.documentId)
    && typeof item.body.clientRequestId === 'string' && Number.isSafeInteger(item.body.expectedStateRevision) && item.body.expectedStateRevision >= 0
}

function mergeSourceRows(previous: AssistantSourceImportSummary[], incoming: AssistantSourceImportSummary[], append = false) {
  const known=new Map(previous.map(item => [item.documentId,item]))
  const result=new Map((append ? previous : []).map(item => [item.documentId,item]))
  for (const item of incoming) {
    const current=known.get(item.documentId)
    result.set(item.documentId,current && current.stateRevision>item.stateRevision ? current : item)
  }
  return [...result.values()]
}

/** 原文件只留在文件选择器；持久化日志仅保存身份、指纹及原命令，不把超时当成失败。 */
export function AssistantSourceImportPanel(props: Props) {
  const {store,sessionId,actorId,disabled,direct} = props
  const {active = true,selectionActivation,onSelection} = props.direct ? {} : props
  const api = store.api
  const namespace = api.transport.storageNamespace?.() || api.transport.kind
  const journalKey = `aid:assistant-source:v1:${namespace}:${actorId}:${sessionId}`
  const [items,setItems] = useState<AssistantSourceImportSummary[]>([])
  const [cursor,setCursor] = useState<string | null>(null)
  const [selected,setSelected] = useState<string[]>([])
  const [pending,setPending] = useState<AssistantSourcePending | null>(null)
  const [cancellation,setCancellation] = useState<AssistantSourceAdvanceRequest | null>(null)
  const [cancelling,setCancelling] = useState(false)
  const [journalBroken,setJournalBroken] = useState(false)
  const [file,setFile] = useState<File | null>(null)
  const [detail,setDetail] = useState<AssistantSourceImportDetail | null>(null)
  const [chunks,setChunks] = useState<AssistantArtifactChunk[]>([])
  const [reader,setReader] = useState<AssistantSourceRevision | null>(null)
  const [readingComplete,setReadingComplete] = useState(false)
  const [busy,setBusy] = useState(false)
  const [error,setError] = useState<string | null>(null)
  const [notice,setNotice] = useState<string | null>(null)
  const [directDocument, setDirectDocument] = useState<AssistantSourceImportSummary | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const live = useRef(true), inFlight = useRef(false), pendingRef = useRef<AssistantSourcePending | null>(null), selectedRef = useRef<string[]>([])
  const cancellationRef = useRef<AssistantSourceAdvanceRequest | null>(null), cancelFlight = useRef(false)
  const selectionCallback = useRef(onSelection)
  const onAttach = props.direct ? props.onAttach : undefined
  const onBusyChange = props.direct ? props.onBusyChange : undefined
  const attachCallback = useRef(onAttach)
  useEffect(() => {selectionCallback.current=onSelection; attachCallback.current=onAttach},[onSelection,onAttach])
  const save = (next: AssistantSourcePending | null, selection = selectedRef.current) => {
    // 必须在网络提交前成功记录，否则刷新可能丢失原幂等键。
    localStorage.setItem(journalKey,JSON.stringify({version:1,pending:next,cancellation:cancellationRef.current,selected:selection} satisfies Journal))
    pendingRef.current = next; setPending(next); selectedRef.current = selection; setSelected(selection)
  }
  const saveCancellation = (next: AssistantSourceAdvanceRequest | null) => {
    localStorage.setItem(journalKey,JSON.stringify({version:1,pending:pendingRef.current,cancellation:next,selected:selectedRef.current} satisfies Journal))
    cancellationRef.current=next;setCancellation(next)
  }
  const load = async (append = false) => {
    const page = await api.sourceImportPage(sessionId,append ? cursor || undefined : undefined)
    const visible=new Set(page.items.map(item => item.documentId))
    const restored=append ? [] : await Promise.all(selectedRef.current.filter(id => !visible.has(id)).map(id => api.sourceImportDetail(sessionId,id)))
    if (!live.current) return
    setItems(previous => mergeSourceRows(previous,[...page.items,...restored],append))
    setCursor(page.nextAfterDocumentId)
  }
  const operate = async (action: () => Promise<unknown>) => {
    if (inFlight.current || disabled) return
    inFlight.current = true; setBusy(true); setError(null)
    onBusyChange?.(true)
    try { await action() } catch (cause) { if (live.current) setError(assistantErrorMessage(cause)) }
    finally { inFlight.current = false; if (live.current) { setBusy(false); onBusyChange?.(false) } }
  }
  useEffect(() => {
    live.current = true
    try {
      const raw = localStorage.getItem(journalKey), saved: Journal | null = raw ? JSON.parse(raw) : null
      if (saved && (saved.version !== 1 || !Array.isArray(saved.selected) || saved.selected.length > 20
        || saved.selected.some(id => typeof id !== 'string' || !/^[1-9][0-9]*$/.test(id))
        || saved.pending !== null && !validPending(saved.pending,sessionId)
        || saved.cancellation != null && !validPending({kind:'CANCEL',body:saved.cancellation},sessionId))) throw new Error('文档恢复记录无效，请核查后再操作')
      const next = saved?.pending || null; pendingRef.current = next; setPending(next)
      selectedRef.current = saved?.selected || []; setSelected(selectedRef.current)
      cancellationRef.current=saved?.cancellation || null;setCancellation(cancellationRef.current)
      if (!direct) void load().catch(cause => { if (live.current) setError(assistantErrorMessage(cause)) })
    } catch (cause) { setJournalBroken(true);setError(assistantErrorMessage(cause)) }
    // Closing or remounting the picker must not consume the composer's selected references.
    return () => { live.current = false; if (inFlight.current) onBusyChange?.(false) }
    // 会话组件按身份与会话 remount，API 与日志键在该生命周期内固定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [journalKey, direct])
  useEffect(() => {
    selectionCallback.current?.(items.filter(item => selected.includes(item.documentId) && item.status === 'FINALIZED').map(sourceDocumentContext))
    // Sending consumes the composer draft, while the picker keeps its saved selection.
    // A retained modal may freeze children while closed, hiding active=false. The host's
    // explicit opening revision also reapplies visibly checked documents to the next draft.
  }, [items,selected,active,selectionActivation])
  const apply = async (result: AssistantSourceImportCommand, cancellationResult = false) => {
    if (!live.current) return
    const row = result.document
    if (direct) {
      setDirectDocument(['FINALIZED', 'CANCELLED'].includes(row.status) ? null : row)
      if (!cancellationResult && result.status === 'COMPLETED' && row.status === 'FINALIZED') attachCallback.current?.(sourceDocumentContext(row))
    } else {
      setItems(previous => mergeSourceRows(previous,[row],true))
      setDetail(previous => previous?.documentId===row.documentId && previous.stateRevision>row.stateRevision ? previous : row)
    }
    if (result.status !== 'PROCESSING') {if (cancellationResult) saveCancellation(null);else save(null)}
    if (result.status === 'FAILED') {
      const code = result.errorCode || row.errorCode || ''
      setError(screenplayErrors[code] || errors[code] || '解析未完成，请查看失败分片后重试。')
    }
    else setNotice(result.status === 'PROCESSING' ? '原操作仍在执行，请继续核实。' : labels[row.status])
  }
  useEffect(() => {
    if (pending?.kind !== 'ADVANCE') return
    let active=true, timer: ReturnType<typeof setTimeout> | undefined
    const pump=async () => {
      try {
        const value=await api.sourceImportDetail(sessionId,pending.body.documentId)
        if (!active || !live.current) return
        if (!direct) setItems(previous => mergeSourceRows(previous,[value],true))
        if (!['DISCOVERING', 'PARSING', 'FINALIZING'].includes(value.status)) {
          const lookup=await api.sourceImportLookup(sessionId,pending.body.clientRequestId)
          if (active && lookup.command && lookup.command.status !== 'PROCESSING') await apply(lookup.command)
        }
      } catch { /* 原请求仍待核实，轮询失败不改写命令结果或生成新标识。 */ }
      if (active) timer=setTimeout(() => void pump(),3000)
    }
    void pump()
    return () => {active=false;if (timer) clearTimeout(timer)}
    // 同一待核实命令只持有一个轮询链，API 层再次合并相同进行中读取。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pending,api,sessionId,direct])
  const executePending = async (value: AssistantSourcePending, sourceFile = file, knownHash?: string) => {
    try {
      let result: AssistantSourceImportCommand
      if (value.kind === 'UPLOAD') {
        if (!sourceFile || sourceFile.name !== value.fileName || sourceFile.size !== value.byteCount) throw new Error('请重新选择原文件；保持原上传标识核实。')
        const hash = knownHash ?? await hashFile(sourceFile)
        if (hash !== value.fileHash) throw new Error('所选文件与原上传指纹不同，请选择原文件。')
        const original=new File([sourceFile],value.fileName,{type:value.mediaType})
        result = await api.sourceImportUpload(sessionId,value.clientRequestId,original,hash)
      } else result = await (value.kind === 'ADVANCE' ? api.sourceImportAdvance(value.body) : api.sourceImportCancel(value.body))
      await apply(result)
      return result
    } catch (cause) {
      // 只有权威明确拒绝才解除待核实状态；5xx/断线/超时保留原正文。
      if (assistantCommandWasRejected(cause)) save(null)
      throw cause
    }
  }
  const recover = async (sourceFile = file) => {
    const value = pendingRef.current; if (!value) return
    const key = value.kind === 'UPLOAD' ? value.clientRequestId : value.body.clientRequestId
    const observed = await api.sourceImportLookup(sessionId,key)
    if (observed.command) { await apply(observed.command); return observed.command }
    return executePending(value, sourceFile)
  }
  const upload = async (sourceFile = file) => {
    if (!sourceFile || pendingRef.current) return
    if (!/\.(txt|md|docx|pdf)$/i.test(sourceFile.name) || sourceFile.size < 1 || sourceFile.size > 10 * 1024 * 1024) throw new Error('请选择 10 MB 以内的 TXT、Markdown、DOCX 或 PDF 文件。')
    const hash = await hashFile(sourceFile)
    if (!live.current) return
    const value: AssistantSourcePending = {kind:'UPLOAD',sessionId,clientRequestId:assistantCommandId(),fileName:sourceFile.name,mediaType:sourceFile.type || 'application/octet-stream',fileHash:hash,byteCount:sourceFile.size}
    save(value); return executePending(value, sourceFile, hash)
  }
  const advance = async (row: AssistantSourceImportSummary, cancel = false) => {
    if (pendingRef.current) return
    const current = await api.sourceImportDetail(sessionId,row.documentId)
    if (!live.current) return
    const value: AssistantSourcePending = {kind:cancel ? 'CANCEL' : 'ADVANCE',body:{sessionId,documentId:current.documentId,expectedStateRevision:current.stateRevision,clientRequestId:assistantCommandId(),...(cancel ? {} : {maxParts:20})}}
    save(value); return executePending(value)
  }
  const finishDirectImport = async (result?: AssistantSourceImportCommand) => {
    // Each accepted batch advances the authoritative revision. Yield to an explicit continue for very large files.
    for (let batch = 0; batch < 4 && live.current && result?.status === 'COMPLETED'
      && result.document.allowedActions.includes('ADVANCE'); batch += 1) {
      result = await advance(result.document)
    }
  }
  const cancelDocument = async (row?: AssistantSourceImportSummary) => {
    if (cancelFlight.current || disabled || journalBroken) return
    cancelFlight.current=true;setCancelling(true);setError(null)
    try {
      let body=cancellationRef.current
      if (body) {
        const lookup=await api.sourceImportLookup(sessionId,body.clientRequestId)
        if (lookup.command) {await apply(lookup.command,true);return}
      } else {
        if (!row) return
        const current=await api.sourceImportDetail(sessionId,row.documentId)
        body={sessionId,documentId:row.documentId,expectedStateRevision:current.stateRevision,clientRequestId:assistantCommandId()}
        saveCancellation(body)
      }
      try {await apply(await api.sourceImportCancel(body),true)}
      catch (cause) {if (assistantCommandWasRejected(cause)) saveCancellation(null);throw cause}
    } catch (cause) {if (live.current) setError(assistantErrorMessage(cause))}
    finally {cancelFlight.current=false;if (live.current) setCancelling(false)}
  }
  const inspect = async (row: AssistantSourceImportSummary, after = -1) => {
    let value = await api.sourceImportDetail(sessionId,row.documentId,after)
    // A changed revision needs its first page; a late tail page cannot claim a complete part list.
    if (after >= 0 && value.stateRevision !== row.stateRevision) {
      value = await api.sourceImportDetail(sessionId,row.documentId)
    }
    if (live.current) setDetail(previous => previous?.documentId===value.documentId && previous.stateRevision>value.stateRevision ? previous
      : after>=0 && previous?.documentId===value.documentId && previous.stateRevision===value.stateRevision ? {...value,parts:[...previous.parts,...value.parts]} : value)
  }
  const read = async (row: AssistantSourceImportSummary, more = false) => {
    const source = more ? reader : null, previous = more ? chunks : []
    const page = await api.sourceImportContent(sessionId,row.documentId,previous.at(-1)?.chunkIndex ?? -1)
    const pinned = source || sourceImportRevisionForReading(row, await api.sourceImportDetail(sessionId,row.documentId))
    const bound: AssistantSourceRevision = source || {...pinned,chunkCount:page.chunkCount}
    if (page.sourceRevisionId !== row.sourceRevisionId) throw new Error('原文版本已变化，请重新打开')
    const merged = await mergeAssistantSourceContent(bound,previous,page)
    if (live.current) {setReader(bound);setChunks(merged.chunks);setReadingComplete(merged.complete)}
  }
  if (direct) return <>
    <input ref={fileInput} hidden type="file" aria-label="选择参考文件" accept=".txt,.md,.docx,.pdf" onChange={event => {
      const sourceFile = event.currentTarget.files?.[0]
      event.currentTarget.value = ''
      if (!sourceFile) return
      void operate(async () => finishDirectImport(await (pendingRef.current?.kind === 'UPLOAD' ? recover(sourceFile) : upload(sourceFile))))
    }} />
    {(busy || error || pending || cancellation || directDocument) ? <div className="assistant-source-import-status">
      {busy ? <span role="status">正在上传或解析附件…</span> : error ? <span role="alert">{error}</span> : null}
      {!busy && pending && pending.kind !== 'UPLOAD' ? <button type="button" disabled={disabled} onClick={() => void operate(async () => finishDirectImport(await recover()))}>核实附件解析</button> : null}
      {!busy && pending?.kind === 'UPLOAD' ? <span role="status">上传待核实，请重新选择同一文件。</span> : null}
      {!busy && cancellation ? <button type="button" disabled={disabled || cancelling} onClick={() => void cancelDocument()}>核实附件取消</button> : null}
      {!busy && !pending && !cancellation && directDocument ? <>
        {directDocument.allowedActions.some(action => action === 'ADVANCE' || action === 'RETRY') ? <button type="button" disabled={disabled} onClick={() => void operate(async () => finishDirectImport(await advance(directDocument)))}>{directDocument.status === 'FAILED' ? '重试解析' : '继续解析'}</button> : null}
        {directDocument.allowedActions.includes('CANCEL') ? <button type="button" disabled={disabled || cancelling} onClick={() => void cancelDocument(directDocument)}>取消解析</button> : null}
      </> : null}
    </div> : null}
    <button type="button" className="assistant-source-import-trigger" aria-label="上传文本附件" disabled={busy || disabled || journalBroken || !!cancellation || (!!pending && pending.kind !== 'UPLOAD')}
      onClick={() => fileInput.current?.click()}><PaperClipOutlined aria-hidden /></button>
  </>
  return <section className="assistant-source-import rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.1))] p-3 [&_.ant-btn]:min-h-11" aria-label="参考文件">
    <h3 className="m-0 text-sm font-medium">参考文件</h3>
    <p className="my-2 text-xs leading-5">支持 TXT、Markdown、DOCX 和有文本层的 PDF（最大 10 MB）。解析不调用模型。选中文档后，在下一条新需求中引用；用户阅读不算助手已读。</p>
    <label className="block text-xs">选择文件<input aria-label="选择参考文件" type="file" accept=".txt,.md,.docx,.pdf" disabled={busy || disabled} onChange={event => setFile(event.target.files?.[0] || null)} className="mt-1 block min-h-11 w-full max-w-full" /></label>
    <div className="flex flex-wrap gap-2"><Button disabled={busy || disabled || journalBroken || !file || !!pending || !!cancellation} onClick={() => void operate(() => upload())}>上传参考文件</Button><Button disabled={busy || disabled} onClick={() => void operate(() => load())}>刷新文档状态</Button></div>
    {pending ? <div className="my-2 text-xs leading-5"><p>上次文档操作结果待核实，刷新后保留原请求。上传时需重新选择同一文件。</p><Button disabled={busy || disabled} onClick={() => void operate(() => recover())}>核实原文档操作</Button></div> : null}
    {cancellation ? <div className="my-2 text-xs leading-5"><p>解析取消结果待核实，原解析和取消标识分别保存。</p><Button disabled={cancelling || disabled} onClick={() => void cancelDocument()}>核实原取消操作</Button></div> : null}
    {error ? <p role="alert" className="text-xs leading-5 text-[var(--studio-danger,#fde68a)]">{error}</p> : null}
    {notice ? <p role="status" aria-live="polite" className="text-xs">{notice}</p> : null}
    {items.map(row => <article key={row.documentId} className="my-3 min-w-0 border-t border-[var(--studio-ui-border,rgba(255,255,255,0.1))] pt-2 text-xs">
      <p className="m-0 break-words">{row.originalName} · {labels[row.status]} · 已解析 {row.readyPartCount}/{row.partCount} 片{row.failedPartCount ? ` · 失败 ${row.failedPartCount} 片` : ''}</p>
      <progress aria-label={`${row.originalName}解析进度`} max={Math.max(1,row.partCount)} value={row.readyPartCount} className="my-2 w-full" />
      {row.errorCode ? <p role="status">{errors[row.errorCode] || '解析未完成，可查看失败分片。'}</p> : null}
      <div className="flex flex-wrap items-center gap-2">
        {row.status === 'FINALIZED' ? <label className="flex min-h-11 items-center gap-2"><input type="checkbox" disabled={busy || disabled || journalBroken || selected.length >= 20 && !selected.includes(row.documentId)} checked={selected.includes(row.documentId)} onChange={event => {try {save(pendingRef.current,event.target.checked ? [...selected,row.documentId] : selected.filter(id => id !== row.documentId))} catch (cause) {setError(assistantErrorMessage(cause))}}} />引用完整文档</label> : null}
        {row.allowedActions.some(action => ['ADVANCE','RETRY'].includes(action)) ? <Button disabled={busy || disabled || journalBroken || !!pending || !!cancellation} onClick={() => void operate(() => advance(row))}>{row.status === 'FAILED' ? '重试失败分片' : '继续解析'}</Button> : null}
        {row.allowedActions.includes('CANCEL') ? <Button disabled={cancelling || disabled || journalBroken || !!cancellation || !!pending && (pending.kind !== 'ADVANCE' || pending.body.documentId !== row.documentId)} onClick={() => void cancelDocument(row)}>取消文档解析</Button> : null}
        <Button disabled={busy || disabled} onClick={() => void operate(() => inspect(row))}>分片状态</Button>
        {row.status === 'FINALIZED' ? <Button disabled={busy || disabled} onClick={() => void operate(() => read(row))}>阅读原文</Button> : null}
      </div>
    </article>)}
    {cursor ? <Button disabled={busy || disabled} onClick={() => void operate(() => load(true))}>更多文档</Button> : null}
    {detail ? <div className="max-h-64 overflow-y-auto text-xs" aria-label="文档分片状态"><p>{detail.originalName}的分片</p>{detail.parts.map(part => <p key={part.partIndex}>第 {part.partIndex+1} 片 · {part.status === 'READY' ? '已解析' : part.status === 'FAILED' ? '失败' : part.status === 'CANCELLED' ? '已取消' : '待解析'} · 尝试 {part.attemptCount}/3{part.errorCode ? ' · '+(errors[part.errorCode] || '解析失败') : ''}</p>)}{!detail.partsComplete && detail.nextAfterPartIndex !== null ? <Button disabled={busy || disabled} onClick={() => void operate(() => inspect(detail,detail.nextAfterPartIndex!))}>更多分片</Button> : null}</div> : null}
    {reader ? <div aria-label="参考文档正文" className="mt-3 text-xs"><p>{readingComplete ? '完整原文已校验' : '正在阅读部分原文'}</p><pre className="max-h-80 overflow-y-auto whitespace-pre-wrap break-words font-inherit">{chunks.map(chunk => chunk.content).join('')}</pre>{!readingComplete ? <Button disabled={busy || disabled} onClick={() => void operate(async () => {
      const row=items.find(item => item.documentId === reader.sourceObjectId) || await api.sourceImportDetail(sessionId,reader.sourceObjectId)
      await read(row,true)
    })}>继续阅读原文</Button> : null}</div> : null}
  </section>
}

async function hashFile(file: File) {
  return assistantSha256(new Uint8Array(await file.arrayBuffer()))
}
