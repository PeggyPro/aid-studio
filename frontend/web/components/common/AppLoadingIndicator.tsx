import './app-loading-indicator.css'

export function AppLoadingIndicator({ label, brand, inline = false }: { label: string; brand?: string; inline?: boolean }) {
  return <div className={`aid-loading-indicator${inline ? ' aid-loading-indicator--inline' : ''}`}>
    <div className="aid-loading-indicator__copy">
      {brand ? <span className="aid-loading-indicator__brand">{brand}</span> : null}
      <span className="aid-loading-indicator__label">{label}</span>
    </div>
    <div className="aid-loading-indicator__bar" aria-hidden="true"><i /></div>
  </div>
}
