const INDEPENDENT_THEME_PATH = /^\/(?:3d-box|studio|canvas|mobile)(?:\/|$)/
const ABOUT_PATH = /^\/about(?:\/|$)/
const PUBLIC_CANVAS_WORK_PATH = '/canvas/work'

function normalizePath(pathname: string) {
  return pathname.split('?')[0].replace(/\/+$/, '') || '/'
}

function isIndependentThemePath(path: string) {
  return path !== PUBLIC_CANVAS_WORK_PATH && (INDEPENDENT_THEME_PATH.test(path) || path === '/create/studio')
}

export function resolveV10Surface(pathname: string, preference: 'graphite' | 'paper' = 'graphite'): 'v10' | 'home' | 'original' {
  const path = normalizePath(pathname)
  if (path === '/') return 'home'
  if (ABOUT_PATH.test(path)) return preference === 'paper' ? 'v10' : 'original'
  return isIndependentThemePath(path) ? 'original' : 'v10'
}

export function supportsV10Theme(pathname: string): boolean {
  return !isIndependentThemePath(normalizePath(pathname))
}

/** 首帧与客户端导航使用同一套路径范围；公开作品详情跟随新版主题。 */
export const V10_THEME_BOOTSTRAP_SCRIPT = `(()=>{const p=location.pathname.replace(/\\/+$/,'')||'/',h=document.documentElement,independent=p!=='${PUBLIC_CANVAS_WORK_PATH}'&&(${INDEPENDENT_THEME_PATH}.test(p)||p==='/create/studio'),about=${ABOUT_PATH}.test(p);h.dataset.aidUi=p==='/'?'home':independent||about?'original':'v10';if(independent)return;let t='graphite';try{if(localStorage.getItem('aid-home-theme')==='paper')t='paper'}catch{}h.dataset.v10Theme=t;if(about&&t==='paper')h.dataset.aidUi='v10'})()`
