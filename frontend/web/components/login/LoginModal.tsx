'use client'

/* eslint-disable @next/next/no-img-element -- 站点配置提供的品牌图片沿用原始资源地址 */

import { useState } from 'react'
import { ConfigProvider, message } from 'antd'
import { usePathname, useRouter } from 'next/navigation'
import AidHoverLogo from '@/components/atoms/AidHoverLogo'
import logoMod from '~/assets/img/home/logo-new.svg'
import { LoginAltMethods } from './LoginAltMethods'
import { LoginCodePanel } from './LoginCodePanel'
import { LoginPasswordPanel } from './LoginPasswordPanel'
import { LoginWechatPanel } from './LoginWechatPanel'
import { useLoginModalFitScale } from '@/hooks/useLoginModalFitScale'
import { useLoginModalSession } from '@/hooks/useLoginModalSession'
import { assetUrl } from '~/utils/assetUrl'
import { closeLoginModal, enterForgotPasswordFlow } from '@/stores/loginModal'
import { LOGIN_MODAL_ANTD_THEME } from '~/utils/loginModalAntdTheme'
import {
  LOGIN_MODAL_BODY_CLASS,
  LOGIN_MODAL_BRAND_CLASS,
  LOGIN_MODAL_CLOSE_CLASS,
  LOGIN_MODAL_DESIGN_HEIGHT,
  LOGIN_MODAL_DESIGN_WIDTH,
  LOGIN_MODAL_FIT_SHELL_CLASS,
  LOGIN_MODAL_FORM_CLASS,
  LOGIN_MODAL_FRAME_CLASS,
  LOGIN_MODAL_OVERLAY_CLASS,
  LOGIN_MODAL_TITLE_CLASS,
  LOGIN_MODAL_VISUAL_CLASS
} from '~/utils/loginModalLayout'
import { LOGIN_VISUAL_VIDEO_URLS, nextLoginVisualVideoIndex } from '~/utils/loginVisualVideos'

const MOBILE_LOGO_URL = assetUrl(logoMod)
const LOGIN_VISUAL_POSTER = '/media/login/poster.webp'

const TITLE_MAP = {
  code: '验证码登录',
  wechat: '微信扫码登录',
  password: '密码登录'
} as const

function LoginModalVisual() {
  const [playlist, setPlaylist] = useState({ index: 0, consecutiveFailures: 0 })
  const src = LOGIN_VISUAL_VIDEO_URLS[playlist.index]
  const advance = (failed: boolean) => setPlaylist((current) => {
    if (current.index !== playlist.index) return current
    return {
      index: nextLoginVisualVideoIndex(current.index),
      consecutiveFailures: failed ? current.consecutiveFailures + 1 : 0
    }
  })

  return (
    <section className={LOGIN_MODAL_VISUAL_CLASS} aria-hidden="true" style={{ background: "center / cover no-repeat url(" + LOGIN_VISUAL_POSTER + ")" }}>
      {playlist.consecutiveFailures < LOGIN_VISUAL_VIDEO_URLS.length ? <video
        key={src}
        className="h-full w-full object-cover"
        src={src}
        poster={LOGIN_VISUAL_POSTER}
        autoPlay
        muted
        playsInline
        preload="auto"
        disablePictureInPicture
        controlsList="nodownload noplaybackrate nofullscreen noremoteplayback"
        onEnded={() => advance(false)}
        onError={() => advance(true)}
      /> : null}
    </section>
  )
}

interface LoginModalProps {
  /** Host 控制的进场可见态（退场时先 false 再卸载） */
  visible?: boolean
}

export function LoginModal({ visible = true }: LoginModalProps) {
  const router = useRouter()
  const pathname = usePathname() ?? ''
  const mobile = pathname === '/mobile' || pathname === '/mobile/desktop'
  const session = useLoginModalSession({ smsOnly: mobile })
  const fitScale = useLoginModalFitScale()
  const {
    tab,
    selectTab,
    siteName,
    platformLogoUrl,
    codeLoginPresentation,
    wechatLoginEnabled
  } = session

  function handleOverlayClick(e: React.MouseEvent<HTMLDivElement>) {
    if (e.target === e.currentTarget) closeLoginModal()
  }

  function handleForgotPassword() {
    if (!codeLoginPresentation.enabled) {
      message.warning('验证码登录未开启，无法找回密码')
      return
    }
    enterForgotPasswordFlow(router)
  }

  if (mobile) return (
    <div className="login-modal-overlay mobile-login-overlay fixed inset-0 z-[5000] flex items-center justify-center" data-state={visible ? 'open' : 'closed'} role="presentation" onClick={handleOverlayClick}>
      <section className="mobile-login-card" role="dialog" aria-modal="true" aria-label="登录" onClick={(event) => event.stopPropagation()}>
        <button type="button" className="mobile-login-close" aria-label="关闭登录" onClick={closeLoginModal}>×</button>
        <div className="mobile-login-brand">
          <img src={platformLogoUrl || MOBILE_LOGO_URL} alt="视觉 AID" />
        </div>
        <h2>欢迎回来</h2>
        <p className="mobile-login-subtitle">登录后继续浏览与创作</p>
        <div className="mobile-login-tabs" role="tablist" aria-label="登录方式">
          <button type="button" role="tab" aria-selected={tab === 'password'} onClick={() => selectTab('password')}>账号密码</button>
          <button type="button" role="tab" aria-selected={tab === 'code' && codeLoginPresentation.enabled} disabled={!codeLoginPresentation.enabled} onClick={() => selectTab('code')}>手机验证码</button>
        </div>
        <ConfigProvider theme={LOGIN_MODAL_ANTD_THEME} wave={{ disabled: true }}>
          <div id="login-captcha-box" className="mobile-login-captcha" aria-hidden="true" />
          {tab === 'code' && codeLoginPresentation.enabled ? <LoginCodePanel
            account={session.account} code={session.code} accountPlaceholder="请输入手机号" registrationHint={codeLoginPresentation.registrationHint}
            loading={session.loading} sendCodeLoading={session.sendCodeLoading} sendCodeCountdown={session.sendCodeCountdown} codeMaxLength={session.loginCodeMaxLength}
            termsOfServiceUrl={session.termsOfServiceUrl} privacyPolicyUrl={session.privacyPolicyUrl}
            onAccountChange={session.setAccount} onCodeChange={session.setCode} onSendCode={() => void session.handleSendCode()}
            onSubmit={() => void session.handleSubmit()} onUnlockAutofill={session.clearLoginInputReadonly} mobileOnly
          /> : <LoginPasswordPanel
            account={session.account} password={session.password} loading={session.loading} showRegister={false} mobileOnly
            termsOfServiceUrl={session.termsOfServiceUrl} privacyPolicyUrl={session.privacyPolicyUrl}
            onAccountChange={session.setAccount} onPasswordChange={session.setPassword} onSubmit={() => void session.handleSubmit()}
            onRegister={() => selectTab('code')} onForgotPassword={handleForgotPassword} onUnlockAutofill={session.clearLoginInputReadonly}
          />}
        </ConfigProvider>
      </section>
    </div>
  )

  return (
    <div
      className={LOGIN_MODAL_OVERLAY_CLASS}
      data-state={visible ? 'open' : 'closed'}
      role="presentation"
      onClick={handleOverlayClick}
    >
      <div
        className={LOGIN_MODAL_FIT_SHELL_CLASS}
        style={{
          width: LOGIN_MODAL_DESIGN_WIDTH * fitScale,
          height: LOGIN_MODAL_DESIGN_HEIGHT * fitScale
        }}
        onClick={(e) => e.stopPropagation()}
      >
      <div
        className={LOGIN_MODAL_FRAME_CLASS}
        role="dialog"
        aria-modal="true"
        aria-label={TITLE_MAP[tab]}
        style={{
          transform: `scale(${fitScale})`,
          transformOrigin: 'top left'
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <LoginModalVisual />

        <section className={LOGIN_MODAL_FORM_CLASS}>
          <ConfigProvider theme={LOGIN_MODAL_ANTD_THEME} wave={{ disabled: true }}>
          <button
            type="button"
            aria-label="关闭"
            className={LOGIN_MODAL_CLOSE_CLASS}
            onClick={() => closeLoginModal()}
          >
            <svg viewBox="0 0 24 24" className="h-8 w-8" fill="none" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </button>

          <div
            id="login-captcha-box"
            className="absolute left-1/2 top-28 z-20 -translate-x-1/2"
            aria-hidden="true"
          />

          <div className={LOGIN_MODAL_BODY_CLASS}>
            <div className={LOGIN_MODAL_BRAND_CLASS}>
              {platformLogoUrl ? (
                <img
                  src={platformLogoUrl}
                  alt={siteName || 'AID'}
                  className="h-16 w-16 object-contain"
                />
              ) : (
                <AidHoverLogo className="!h-16 !w-16 !min-w-0" alt={siteName || 'AID'} />
              )}
              <h2 className={LOGIN_MODAL_TITLE_CLASS}>{TITLE_MAP[tab]}</h2>
            </div>
            <div className="flex w-full justify-center">
              {tab === 'code' && (
                <LoginCodePanel
                  account={session.account}
                  code={session.code}
                  accountPlaceholder={codeLoginPresentation.accountPlaceholder}
                  registrationHint={codeLoginPresentation.registrationHint}
                  loading={session.loading}
                  sendCodeLoading={session.sendCodeLoading}
                  sendCodeCountdown={session.sendCodeCountdown}
                  codeMaxLength={session.loginCodeMaxLength}
                  termsOfServiceUrl={session.termsOfServiceUrl}
                  privacyPolicyUrl={session.privacyPolicyUrl}
                  onAccountChange={session.setAccount}
                  onCodeChange={session.setCode}
                  onSendCode={() => void session.handleSendCode()}
                  onSubmit={() => void session.handleSubmit()}
                  onUnlockAutofill={session.clearLoginInputReadonly}
                />
              )}
              {tab === 'password' && (
                <LoginPasswordPanel
                  account={session.account}
                  password={session.password}
                  loading={session.loading}
                  showRegister={codeLoginPresentation.enabled}
                  termsOfServiceUrl={session.termsOfServiceUrl}
                  privacyPolicyUrl={session.privacyPolicyUrl}
                  onAccountChange={session.setAccount}
                  onPasswordChange={session.setPassword}
                  onSubmit={() => void session.handleSubmit()}
                  onRegister={() => selectTab('code')}
                  onForgotPassword={handleForgotPassword}
                  onUnlockAutofill={session.clearLoginInputReadonly}
                />
              )}
              {tab === 'wechat' && (
                <LoginWechatPanel
                  state={session.wechat.state}
                  enabled={wechatLoginEnabled}
                  termsOfServiceUrl={session.termsOfServiceUrl}
                  privacyPolicyUrl={session.privacyPolicyUrl}
                  onRefresh={() => void session.wechat.openWechatLogin()}
                />
              )}
            </div>
          </div>

          <LoginAltMethods
            current={tab}
            onSelect={selectTab}
            showWechat={wechatLoginEnabled}
            showCode={codeLoginPresentation.enabled}
            showPassword
          />
          </ConfigProvider>
        </section>
      </div>
      </div>
    </div>
  )
}
