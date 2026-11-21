import type { AssistantArtifactChunk, AssistantArtifactContent, AssistantArtifactDetail, AssistantArtifactMedia, AssistantArtifactSummary, AssistantSourceRevision, AssistantSourceContent, AssistantSourceManifest } from '~/types/assistant-artifact'
import { assistantSha256 } from './assistantSha256'

export function assistantArtifactTitle(artifact: AssistantArtifactSummary): string {
  return typeof artifact.metadata?.title === 'string' && artifact.metadata.title.trim() ? artifact.metadata.title : artifact.logicalArtifactId
}
const artifactLabels: Record<string, string> = {
  IMAGE: '图片', VIDEO: '视频', AUDIO: '音频', MUSIC: '音乐',
  OUTLINE: '大纲', SCREENPLAY: '剧本', ADAPTATION_NOTES: '改编说明', REVIEW_REPORT: '评审报告', STORY_BIBLE: '创作设定', EPISODE_PLAN: '分集计划', SOURCE_INDEX: '来源索引',
  FINALIZED: '已保存', DRAFT: '草稿', REVIEWED: '已评审', UNREVIEWED: '待评审', PENDING: '待处理', APPROVED: '通过', REJECTED: '未通过',
  PASS: '通过', PASS_WITH_NOTES: '通过（附建议）', FAIL: '未通过', INFO: '建议', WARNING: '注意', ERROR: '问题', BLOCKER: '阻断问题'
}
export function assistantArtifactLabel(value: string): string { return artifactLabels[value] || value }
export async function assistantTextHash(text: string): Promise<string> {
  return assistantSha256(new TextEncoder().encode(text))
}
/** 保留原始换行、空格及UTF16边界，重读片仅接受完全相同内容；不把缺片当全文。 */
export async function mergeAssistantArtifactContent(detail: AssistantArtifactDetail, previous: AssistantArtifactChunk[], page: AssistantArtifactContent) {
  if (page.artifactId !== detail.artifactId || page.contentHash !== detail.contentHash) throw new Error('产物版本已变化，请重新打开该版本')
  return mergeAssistantTextContent(detail, previous, page, detail.status === 'FINALIZED')
}

async function mergeAssistantTextContent(detail: { contentHash: string; chunkCount: number; charCount: number | null }, previous: AssistantArtifactChunk[], page: Pick<AssistantArtifactContent, 'chunks' | 'nextAfterChunkIndex' | 'complete'>, completeAllowed: boolean) {
  const chunks = [...previous]
  for (const chunk of page.chunks) {
    if (!Number.isSafeInteger(chunk.chunkIndex) || chunk.chunkIndex < 0 || typeof chunk.content !== 'string'
      || !Number.isSafeInteger(chunk.charStart) || !Number.isSafeInteger(chunk.charEnd)
      || chunk.charEnd - chunk.charStart !== chunk.content.length || chunk.charCount !== chunk.content.length) throw new Error('正文分片范围无效')
    if (await assistantTextHash(chunk.content) !== chunk.contentHash) throw new Error('正文分片校验失败，请重新读取')
    if (chunk.chunkIndex < chunks.length) {
      const old = chunks[chunk.chunkIndex]
      if (old.contentHash !== chunk.contentHash || old.charStart !== chunk.charStart || old.charEnd !== chunk.charEnd) throw new Error('正文分片存在冲突')
      continue
    }
    if (chunk.chunkIndex !== chunks.length || chunk.charStart !== (chunks.at(-1)?.charEnd || 0)) throw new Error('正文存在缺失分片，请重新读取')
    chunks.push(chunk)
  }
  const last = chunks.at(-1)?.chunkIndex ?? -1
  const nextAfterChunkIndex = page.nextAfterChunkIndex ?? -1
  if (!Number.isSafeInteger(nextAfterChunkIndex) || nextAfterChunkIndex !== last) throw new Error('正文分页游标无效')
  if (page.complete) {
    if (!completeAllowed || chunks.length !== detail.chunkCount || (chunks.at(-1)?.charEnd || 0) !== detail.charCount
      || await assistantTextHash(chunks.map((chunk) => chunk.content).join('')) !== detail.contentHash) throw new Error('全文完整性校验未通过，不能视为完整版本')
  }
  return { chunks, nextAfterChunkIndex, complete: page.complete }
}

export function isAssistantMediaArtifact(artifact: Pick<AssistantArtifactSummary, 'artifactType'>): boolean {
  return ['IMAGE', 'VIDEO', 'AUDIO', 'MUSIC'].includes(artifact.artifactType)
}

/** 不从 metadata/resourceRef 推导地址；播放前绑定权威产物与技术回执。 */
export function verifyAssistantArtifactMedia(detail: AssistantArtifactDetail, media: AssistantArtifactMedia): AssistantArtifactMedia {
  if (!isAssistantMediaArtifact(detail) || detail.status !== 'FINALIZED' || media.artifactId !== detail.artifactId
    || media.artifactType !== detail.artifactType || media.contentHash !== detail.contentHash
    || media.archiveStatus !== 'ARCHIVED' || media.contentHashKind !== 'MEDIA_ARCHIVE_RECEIPT_SHA256'
    || media.creativeQualityVerified !== false || !media.mediaTaskId) throw new Error('媒体产物与归档回执不一致，请重新读取')
  let url: URL
  try { url = new URL(media.resourceUrl) } catch { throw new Error('媒体资源地址无效') }
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) throw new Error('媒体资源地址不可播放')
  return media
}

export function mergeAssistantSourceContent(source: AssistantSourceRevision, previous: AssistantArtifactChunk[], page: AssistantSourceContent) {
  if (page.sourceRevisionId !== source.sourceRevisionId || page.sourceKind !== source.sourceKind
    || page.sourceObjectId !== source.sourceObjectId || page.sourceRevision !== source.sourceRevision
    || page.contentHash !== source.contentHash || page.chunkCount !== source.chunkCount) throw new Error('原文版本或来源已变化，请重新打开')
  return mergeAssistantTextContent(source, previous, page, source.status === 'ACTIVE')
}

/** 绑定实际运行，拒绝矛盾的入库状态；身份均为 wire string，不能压成 JS number。 */
export function verifyAssistantSourceManifest(runId: string, value: AssistantSourceManifest): AssistantSourceManifest {
  const id = (item: unknown, zero = false) => typeof item === 'string' && (zero ? /^(0|[1-9][0-9]*)$/ : /^[1-9][0-9]*$/).test(item)
  if (!value || value.runId !== runId || !Number.isSafeInteger(value.runRevision) || value.runRevision < 0
    || !/^[0-9a-f]{64}$/.test(value.contextDigest) || typeof value.runStatus !== 'string'
    || !Array.isArray(value.allowedActions) || value.allowedActions.some((action) => typeof action !== 'string')
    || !Array.isArray(value.items)) throw new Error('原文引用与当前运行不一致，请刷新')
  for (const item of value.items) {
    if (!item || !['SCRIPT','DOCUMENT'].includes(item.kind) || item.access !== 'READ'
      || (item.kind === 'SCRIPT' ? !id(item.scriptId) || !id(item.projectId) || !id(item.episodeId,true) : !id(item.documentId) || !id(item.projectId,true) || item.selection !== null)
      || !id(item.version) || !/^[0-9a-f]{64}$/.test(item.contentHash)
      || (item.selection !== null && (typeof item.selection !== 'object' || Array.isArray(item.selection)))
      || !(item.snapshotStatus === 'ACTIVE' && item.nextAction === 'READ_SOURCE' && id(item.sourceRevisionId)
        || item.snapshotStatus === 'NOT_INGESTED' && item.nextAction === 'WAIT_FOR_AGENT_INGEST' && item.sourceRevisionId === null)) {
      throw new Error('原文引用的版本或入库状态无效，请重新读取')
    }
  }
  return value
}
