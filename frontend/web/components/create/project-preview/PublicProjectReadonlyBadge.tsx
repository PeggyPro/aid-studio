import { EyeOutlined } from '@ant-design/icons'

export function PublicProjectReadonlyBadge() {
  return (
    <span className="public-project-readonly-badge" aria-label="当前工程只读预览">
      <EyeOutlined />
      只读预览
    </span>
  )
}
