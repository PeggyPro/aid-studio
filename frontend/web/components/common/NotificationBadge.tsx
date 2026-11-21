export function NotificationBadge({ count, dot = false }: { count: number; dot?: boolean }) {
  if (count <= 0) return null
  if (dot) return <span className="app-notification-dot" aria-hidden="true" />
  return <span className="app-notification-badge" role="img" aria-label={`${count} 条未读通知`}>
    {count > 9 ? '•••' : count}
  </span>
}
