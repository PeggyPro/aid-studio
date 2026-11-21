import type { CSSProperties } from 'react'
import './flow-action-icon.css'

/** 单色操作图标沿用原 SVG 轮廓，以按钮的 currentColor 着色；媒体图片不经过此组件。 */
export function FlowActionIcon({ src, className = '' }: { src: string; className?: string }) {
  const style: CSSProperties = {
    flexShrink: 0,
    backgroundColor: 'currentColor', maskImage: `url("${src}")`, WebkitMaskImage: `url("${src}")`,
    maskSize: 'contain', WebkitMaskSize: 'contain', maskPosition: 'center', WebkitMaskPosition: 'center',
    maskRepeat: 'no-repeat', WebkitMaskRepeat: 'no-repeat'
  }
  return <span aria-hidden="true" className={`flow-action-icon ${className}`.trim()} style={style} />
}
