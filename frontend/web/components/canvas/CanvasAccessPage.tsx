'use client'

import { ArrowRightOutlined, LockOutlined } from '@ant-design/icons'
import { Button, Input, Result, Spin, message } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useUserStore } from '@/stores/user'
import { openLoginModal } from '@/stores/loginModal'
import { canvasApplicationApply, canvasShareDetail } from '@/utils/business/canvas'
import type { CanvasApplication, CanvasShareDetail } from '@/types/canvas-api'
import { StudioFreeThemeProvider } from '@/components/studio/theme/StudioFreeThemeProvider'
import { StudioFreeThemeSwitch } from '@/components/studio/theme/StudioFreeThemeSwitch'
import {
  CANVAS_PROJECT_PAGE_REL,
  CANVAS_PROJECT_PAGE_TARGET,
  canvasProjectHref
} from '@/utils/canvas/canvasProjectNavigation'
import { CanvasReadonlySnapshot } from './CanvasReadonlySnapshot'
import './canvas-access-page.css'

const INVITE_TOKEN_KEY = 'aid-canvas-invite-token'
const SHARE_TOKEN_KEY = 'aid-canvas-share-token'

function readFragmentToken(storageKey: string): string {
  const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ''))
  const fromHash = String(fragment.get('token') || '').trim()
  if (fromHash) sessionStorage.setItem(storageKey, fromHash)
  if (window.location.hash) window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}`)
  return fromHash || String(sessionStorage.getItem(storageKey) || '').trim()
}

function errorText(error: unknown, fallback: string) {
  const detail = error as { msg?: string; message?: string }
  return detail?.msg || detail?.message || fallback
}

export function CanvasInviteAccessPage() {
  return <StudioFreeThemeProvider enabled syncWithSiteTheme><CanvasInviteAccessContent /></StudioFreeThemeProvider>
}

function CanvasInviteAccessContent() {
  const router = useRouter()
  const token = useUserStore((state) => state.token)
  const [inviteToken, setInviteToken] = useState('')
  const [messageText, setMessageText] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [application, setApplication] = useState<CanvasApplication | null>(null)

  // Fragment 凭证只能在浏览器挂载后读取，并需立即从地址栏移除。
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => setInviteToken(readFragmentToken(INVITE_TOKEN_KEY)), [])

  async function apply() {
    if (!token) {
      openLoginModal()
      return
    }
    if (!inviteToken || submitting) return
    setSubmitting(true)
    try {
      const result = await canvasApplicationApply({ inviteToken, message: messageText.trim() || undefined })
      setApplication(result)
      sessionStorage.removeItem(INVITE_TOKEN_KEY)
      message.success(result.status === 'APPROVED' ? '你已加入该画布' : '申请已提交')
    } catch (error: unknown) {
      message.error(errorText(error, '提交加入申请失败'))
    } finally {
      setSubmitting(false)
    }
  }

  if (!inviteToken && !application) {
    return <CanvasAccessShell><Result status="warning" title="邀请链接不可用" subTitle="链接缺少邀请凭证，或凭证已被当前浏览器清除。" extra={<Button onClick={() => router.push('/')}>返回首页</Button>} /></CanvasAccessShell>
  }
  if (application) {
    const approved = application.status === 'APPROVED'
    return (
      <CanvasAccessShell>
        <Result
          status={approved ? 'success' : 'info'}
          title={approved ? '已加入自由画布' : '加入申请已提交'}
          subTitle={approved ? '你现在可以进入项目继续协作。' : '项目所有者审批后，画布会出现在“我的作品 > 画布”中。'}
          extra={approved ? <Button type="primary" href={canvasProjectHref(String(application.projectId))} target={CANVAS_PROJECT_PAGE_TARGET} rel={CANVAS_PROJECT_PAGE_REL}>进入画布</Button> : <Button onClick={() => router.push('/works?tab=canvas')}>查看我的画布</Button>}
        />
      </CanvasAccessShell>
    )
  }
  return (
    <CanvasAccessShell>
      <div className="canvas-access-card">
        <span className="canvas-access-card__icon"><LockOutlined /></span>
        <h1>加入自由画布</h1>
        <p>邀请不会直接授予权限。提交申请后，需要项目所有者审批。</p>
        <Input.TextArea value={messageText} maxLength={500} showCount autoSize={{ minRows: 3, maxRows: 6 }} placeholder="给项目所有者留言（可选）" onChange={(event) => setMessageText(event.target.value)} />
        <Button type="primary" size="large" block loading={submitting} onClick={() => void apply()}>{token ? '提交加入申请' : '登录后申请'} <ArrowRightOutlined /></Button>
      </div>
    </CanvasAccessShell>
  )
}

export function CanvasShareAccessPage() {
  return <StudioFreeThemeProvider enabled syncWithSiteTheme><CanvasShareAccessContent /></StudioFreeThemeProvider>
}

function CanvasShareAccessContent() {
  const token = useUserStore((state) => state.token)
  const [shareToken, setShareToken] = useState('')
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<CanvasShareDetail | null>(null)
  const [error, setError] = useState('')
  const [retryRevision, setRetryRevision] = useState(0)

  // Fragment 凭证只能在浏览器挂载后读取，并需立即从地址栏移除。
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => setShareToken(readFragmentToken(SHARE_TOKEN_KEY)), [])

  useEffect(() => {
    if (!shareToken || !token) return
    let active = true
    // 分享详情请求是该 effect 同步的外部系统，加载态与请求生命周期保持一致。
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true)
    setError('')
    void canvasShareDetail(shareToken)
      .then((result) => {
        if (!active) return
        setDetail(result)
        sessionStorage.removeItem(SHARE_TOKEN_KEY)
      })
      .catch((requestError: unknown) => {
        if (active) setError(errorText(requestError, '分享链接已失效或不可访问'))
      })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [retryRevision, shareToken, token])

  const content = useMemo(() => {
    if (!shareToken) return <Result status="warning" title="分享链接不可用" subTitle="链接缺少分享凭证，或凭证已被当前浏览器清除。" />
    if (!token) return <div className="canvas-access-card"><span className="canvas-access-card__icon"><LockOutlined /></span><h1>登录后查看画布</h1><p>该链接是固定的只读快照，不会授予项目编辑权限。</p><Button type="primary" size="large" block onClick={() => openLoginModal()}>登录并查看</Button></div>
    if (loading) return <div className="canvas-access-loading"><Spin size="large" /><span>正在载入画布快照…</span></div>
    if (error) return <Result status="warning" title="分享链接不可用" subTitle={error} extra={<Button onClick={() => setRetryRevision((value) => value + 1)}>重新加载</Button>} />
    if (detail) return <CanvasReadonlySnapshot detail={detail} />
    return null
  }, [detail, error, loading, shareToken, token])

  return detail ? content : <CanvasAccessShell>{content}</CanvasAccessShell>
}

function CanvasAccessShell({ children }: { children: React.ReactNode }) {
  return (
    <main className="canvas-access-page">
      <div className="canvas-access-page__brand">AI·D <span>STUDIO</span></div>
      <StudioFreeThemeSwitch className="canvas-access-page__theme" />
      {children}
    </main>
  )
}
