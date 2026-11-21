'use client'

import { useEffect, useRef, useState } from 'react'
import { Button, Modal } from 'antd'
import type { AssistantApi } from '~/utils/business/assistant'
import type { AssistantAdoptionAdapter, AssistantArtifactChunk, AssistantArtifactDetail, AssistantArtifactMedia, AssistantArtifactReview, AssistantArtifactSummary } from '~/types/assistant-artifact'
import type { AssistantSessionStore } from '~/utils/assistant/assistantSessionStore'
import { AssistantArtifactAdoption } from './AssistantArtifactAdoption'
import { AssistantSourceCoverage } from './AssistantSourceCoverage'
import { assistantErrorMessage } from '~/utils/assistant/assistantRequestCoordinator'
import { assistantArtifactLabel, assistantArtifactTitle, isAssistantMediaArtifact, mergeAssistantArtifactContent, verifyAssistantArtifactMedia } from '~/utils/assistant/assistantArtifacts'

export function AssistantArtifactReader({ artifact, api, store, adoptionAdapter, onClose }: { artifact: AssistantArtifactSummary; api: AssistantApi; store: AssistantSessionStore; adoptionAdapter?: AssistantAdoptionAdapter; onClose: () => void }) {
  const [selectedId, setSelectedId] = useState(artifact.artifactId)
  return <Modal open onCancel={onClose} title="产物阅读与版本" width={960} footer={null} destroyOnHidden
    className="assistant-artifact-dialog" styles={{ body: { maxHeight: '78dvh', overflow: 'auto' } }}>
    {selectedId !== artifact.artifactId ? <Button onClick={() => setSelectedId(artifact.artifactId)}>返回原产物</Button> : null}
    <ArtifactDocument key={selectedId} artifactId={selectedId} api={api} store={store} adoptionAdapter={adoptionAdapter} onPick={setSelectedId} />
  </Modal>
}

function ArtifactDocument({ artifactId, api, store, adoptionAdapter, onPick }: { artifactId: string; api: AssistantApi; store: AssistantSessionStore; adoptionAdapter?: AssistantAdoptionAdapter; onPick: (id: string) => void }) {
  const [detail, setDetail] = useState<AssistantArtifactDetail | null>(null)
  const [media, setMedia] = useState<AssistantArtifactMedia | null>(null)
  const [playbackError, setPlaybackError] = useState(false)
  const [chunks, setChunks] = useState<AssistantArtifactChunk[]>([])
  const [complete, setComplete] = useState(false)
  const [nextAfter, setNextAfter] = useState(-1)
  const [reviews, setReviews] = useState<AssistantArtifactReview[]>([])
  const [reviewCursor, setReviewCursor] = useState<string | null>(null)
  const [versions, setVersions] = useState<AssistantArtifactSummary[]>([])
  const [versionCursor, setVersionCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const alive = useRef(true)
  const action = useRef(false)
  useEffect(() => {
    alive.current = true
    let active = true
    void (async () => {
      try {
        const doc = await api.artifactDetail(artifactId)
        if (doc.artifactId !== artifactId) throw new Error('产物标识不一致，请重新读取')
        const [body, reviewPage, versionPage] = await Promise.all([
          isAssistantMediaArtifact(doc) ? api.artifactMedia(artifactId).then((value) => ({ media: verifyAssistantArtifactMedia(doc, value), content: null }))
            : api.artifactContent(artifactId).then(async (page) => ({ media: null, content: await mergeAssistantArtifactContent(doc, [], page) })),
          api.artifactReviews(artifactId), api.artifactPage({ logicalArtifactId: doc.logicalArtifactId })
        ])
        const content = body.content
        if (!active) return
        setDetail(doc); setMedia(body.media); setPlaybackError(false); setChunks(content?.chunks || []); setComplete(content?.complete || false); setNextAfter(content?.nextAfterChunkIndex ?? -1)
        setReviews(reviewPage.items); setReviewCursor(reviewPage.nextCursor); setVersions(versionPage.items); setVersionCursor(versionPage.nextCursor)
        setError(null)
      } catch (cause) { if (active) setError(assistantErrorMessage(cause)) }
      finally { if (active) setLoading(false) }
    })()
    return () => { active = false; alive.current = false }
  }, [api, artifactId, reload])

  const more = async (kind: 'content' | 'reviews' | 'versions') => {
    if (!detail || action.current) return
    action.current = true; setBusy(true); setError(null)
    try {
      if (kind === 'content' && !isAssistantMediaArtifact(detail)) {
        const value = await api.artifactContent(artifactId, nextAfter)
        const merged = await mergeAssistantArtifactContent(detail, chunks, value)
        if (!alive.current) return
        if (!merged.complete && merged.nextAfterChunkIndex <= nextAfter) throw new Error('尚无新的已保存分片，请稍后重试')
        setChunks(merged.chunks); setComplete(merged.complete); setNextAfter(merged.nextAfterChunkIndex)
      } else if (kind === 'reviews' && reviewCursor) {
        const page = await api.artifactReviews(artifactId, reviewCursor)
        if (!alive.current) return
        if (page.nextCursor === reviewCursor) throw new Error('评审分页游标未前进')
        setReviews((items) => [...new Map([...items, ...page.items].map((item) => [item.reviewId, item])).values()]); setReviewCursor(page.nextCursor)
      } else if (kind === 'versions' && versionCursor) {
        const page = await api.artifactPage({ logicalArtifactId: detail.logicalArtifactId, cursor: versionCursor })
        if (!alive.current) return
        if (page.nextCursor === versionCursor) throw new Error('版本分页游标未前进')
        setVersions((items) => [...new Map([...items, ...page.items].map((item) => [item.artifactId, item])).values()]); setVersionCursor(page.nextCursor)
      }
    } catch (cause) { if (alive.current) setError(assistantErrorMessage(cause)) }
    finally { action.current = false; if (alive.current) setBusy(false) }
  }
  return <div className="assistant-panel assistant-artifact-reader flex min-w-0 flex-col gap-4">
    {loading ? <p role="status">正在读取产物版本…</p> : null}
    {error ? <div role="alert" className="flex flex-wrap items-center gap-2 text-sm"><span>{error}</span><Button disabled={busy || loading} onClick={() => { setLoading(true); setReload((value) => value + 1) }}>重新读取版本</Button></div> : null}
    {detail ? <>
      <header className="flex flex-col gap-2">
        <h2 className="text-base font-medium">{assistantArtifactTitle(detail)}</h2>
        <p className="text-xs">{assistantArtifactLabel(detail.artifactType)} · 版本 {detail.revision} · {assistantArtifactLabel(detail.status)} · 评审：{assistantArtifactLabel(detail.reviewStatus)}</p>
        {!media ? <><p className="text-xs">已读取 {chunks.length} / {detail.chunkCount} 个文本片段 · {detail.charCount == null ? '长度待核验' : detail.charCount.toLocaleString() + ' 字符（含空白）'}</p>
        <p role="status" className="text-sm">{complete ? '全文已读取并校验通过' : '正文尚未完整载入，请继续读取已保存分片'}</p></> : <p role="status" className="text-sm">媒体已归档；技术回执不代表画面、声音或创作质量已通过评审。</p>}
      </header>
      <section className="flex flex-col gap-2" aria-label="产物版本">
        <h3 className="text-sm font-medium">版本</h3>
        <div className="flex flex-wrap gap-2">{versions.map((version) => <Button key={version.artifactId} size="small" type={version.artifactId === artifactId ? 'primary' : 'default'} onClick={() => onPick(version.artifactId)}>版本 {version.revision} · {assistantArtifactLabel(version.reviewStatus)}</Button>)}</div>
        {versionCursor ? <Button disabled={busy} onClick={() => void more('versions')}>更多版本</Button> : null}
      </section>
      {media ? <section aria-label="媒体预览" className="flex min-w-0 flex-col gap-3">
        {media.artifactType === 'IMAGE' ? (
          // eslint-disable-next-line @next/next/no-img-element -- 使用本次服务端鉴权后的归档地址，不经图片代理转发。
          <img src={media.resourceUrl} alt={assistantArtifactTitle(detail)} referrerPolicy="no-referrer" className="max-h-[52dvh] w-full rounded-lg object-contain" onError={() => setPlaybackError(true)} />
        ) : media.artifactType === 'VIDEO' ? <video src={media.resourceUrl} controls playsInline preload="metadata" aria-label={assistantArtifactTitle(detail)} className="max-h-[52dvh] w-full rounded-lg" onError={() => setPlaybackError(true)} />
          : <audio src={media.resourceUrl} controls preload="metadata" aria-label={assistantArtifactTitle(detail)} className="w-full" onError={() => setPlaybackError(true)} />}
        {playbackError ? <p role="alert" className="text-sm">当前浏览器未能读取或播放媒体。可重新读取授权资源，或打开原文件。</p> : null}
        <a href={media.resourceUrl} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer" className="inline-flex min-h-11 items-center underline">打开归档文件</a>
        <p className="text-xs">{media.mimeType || '媒体格式待识别'}{media.fileSize == null ? '' : ' · ' + media.fileSize.toLocaleString() + ' 字节'}{media.durationSeconds == null ? '' : ' · ' + media.durationSeconds + ' 秒'}</p>
        {playbackError ? <Button disabled={loading || busy} onClick={() => { setLoading(true); setReload((value) => value + 1) }}>重新读取媒体</Button> : null}
      </section> : <><section className="assistant-artifact-body rounded-xl border border-[var(--studio-ui-border,rgba(255,255,255,0.2))]" aria-label="产物正文" tabIndex={0}>
        {chunks.map((chunk) => <pre key={chunk.chunkIndex} className="whitespace-pre-wrap break-words font-sans text-sm leading-6">{chunk.content}</pre>)}
        {!chunks.length ? <p className="text-sm">此版本尚无已保存文本分片。</p> : null}
      </section>
      {!complete ? <Button loading={busy} onClick={() => void more('content')}>继续读取正文</Button> : null}</>}
      {!media && adoptionAdapter ? <AssistantArtifactAdoption key={detail.artifactId} artifact={detail} store={store} adapter={adoptionAdapter} complete={complete} /> : null}
      {Array.isArray(detail.sourceContextRefs) ? detail.sourceContextRefs.filter((source) => source.ref === 'source:' + source.sourceRevisionId && /^[1-9]\d*$/.test(source.sourceRevisionId)).map((source) => <AssistantSourceCoverage key={source.ref + ':' + source.purpose} source={source} runId={detail.producerRunId} api={api} onReadEvidence={onPick} />) : null}
      <section className="flex flex-col gap-2" aria-label="产物评审">
        <h3 className="text-sm font-medium">评审记录</h3>
        {!reviews.length ? <p className="text-xs">暂无已保存评审记录。</p> : reviews.map((review) => <article key={review.reviewId} className="assistant-card assistant-card-compact rounded-lg border border-[var(--studio-ui-border,rgba(255,255,255,0.2))]">
          <p className="text-sm">{assistantArtifactLabel(review.verdict)}</p><p className="text-xs">{Object.entries(review.severityCounts || {}).map(([name, count]) => assistantArtifactLabel(name) + '：' + count).join(' · ')}</p>
          <Button size="small" onClick={() => onPick(review.reviewArtifactId)}>阅读评审报告</Button>
        </article>)}
        {reviewCursor ? <Button disabled={busy} onClick={() => void more('reviews')}>更多评审</Button> : null}
      </section>
    </> : null}
  </div>
}
