'use client'

/* eslint-disable @next/next/no-img-element -- 本地品牌/宣传 SVG 需保持静态导出直链行为 */

import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import { message } from 'antd'
import Link from 'next/link'
import { useEffect, useState } from 'react'
import logoMod from '~/assets/img/home/logo-new.svg'
import picMod from '~/assets/img/icon/pic.svg'
import { assetUrl } from '~/utils/assetUrl'
import styles from '../mobile-page.module.css'

const logoUrl = assetUrl(logoMod)
const picUrl = assetUrl(picMod)

/** 手机端进入创作入口后的电脑端提示。 */
export default function MobileDesktopPage() {
  const [desktopUrl, setDesktopUrl] = useState('')
  useEffect(() => { setDesktopUrl(window.location.origin) }, [])

  async function copyPcUrl() {
    if (!desktopUrl) return
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(desktopUrl)
      } else {
        const input = document.createElement('input')
        input.value = desktopUrl
        document.body.appendChild(input)
        input.select()
        document.execCommand('copy')
        document.body.removeChild(input)
      }
      message.success('电脑端地址已复制')
    } catch {
      message.error('复制失败，请手动复制地址')
    }
  }

  return (
    <div className={styles.page}>
      <HtmlShellClass classes="mobile-only-shell" />
      <div className={styles.panel}>
        <img src={logoUrl} alt="AID Studio" className={styles.logo} />
        <div className={styles.hero}>
          <img src={picUrl} alt="用AI重新定义短剧创作" className={styles.heroImage} />
        </div>
        <div className={styles.tip}>
          <p className={styles.tipTitle}>请使用电脑端打开本网页</p>
          <p className={styles.tipDomain}>{desktopUrl}</p>
          <button type="button" className={styles.copyButton} onClick={copyPcUrl} disabled={!desktopUrl}>
            复制电脑端地址
          </button>
          <Link href="/mobile" className={styles.backLink}>返回首页继续浏览</Link>
        </div>
      </div>
    </div>
  )
}
