import Link from 'next/link'
import { ArrowLeftOutlined, LockOutlined } from '@ant-design/icons'

export function StudioReadOnlyTopbar({ title, backHref }: { title: string; backHref: string }) {
  return <header className="studio-topbar studio-readonly-topbar">
    <div className="studio-topbar__left">
      <Link className="studio-readonly-topbar__back" href={backHref} replace aria-label="返回作品"><ArrowLeftOutlined /></Link>
      <strong title={title}>{title}</strong>
    </div>
    <span className="studio-readonly-topbar__status"><LockOutlined />只读画布</span>
  </header>
}
