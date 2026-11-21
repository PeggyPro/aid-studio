import type { StudioNodeStatus } from '@/types/studio'

const STATUS_LABEL: Record<StudioNodeStatus, string> = {
  empty: '待完善',
  queued: '排队中',
  generating: '生成中',
  success: '已完成',
  failed: '需处理'
}

export function StudioStatusBadge({ status, progress }: { status: StudioNodeStatus; progress?: number | null }) {
  const percentage = (status === 'generating' || status === 'queued') && typeof progress === 'number' && Number.isFinite(progress)
    ? Math.max(0, Math.min(95, Math.round(progress))) : null
  return (
    <span className={`studio-status studio-status--${status}`} role="status">
      <span className="studio-status__dot" aria-hidden="true" />
      {STATUS_LABEL[status]}
      {percentage !== null ? <span className="tabular-nums">{percentage}%</span> : null}
    </span>
  )
}
