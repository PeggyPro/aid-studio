export interface PublicSeoMeta {
  title?: string
  description?: string
  keywords?: string
  canonicalUrl?: string
  imageUrl?: string
  robots?: string
}

type SeoOwner = { owner: string; value: PublicSeoMeta }

let baseSeo: PublicSeoMeta = {}
let pageSeo: SeoOwner | null = null
const managedMeta = new WeakSet<HTMLMetaElement>()

function setMeta(attr: 'name' | 'property', key: string, content?: string) {
  let element = document.head.querySelector<HTMLMetaElement>(`meta[${attr}="${key}"]`)
  if (!content) {
    // Next/React 会在路由卸载时回收它渲染的节点。只清除本模块创建的标签。
    if (element && managedMeta.has(element)) element.remove()
    return
  }
  if (!element) {
    element = document.createElement('meta')
    element.setAttribute(attr, key)
    managedMeta.add(element)
    document.head.appendChild(element)
  }
  element.setAttribute('content', content)
}

function setCanonical(href: string) {
  const canonical = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]')
    || document.createElement('link')
  canonical.setAttribute('rel', 'canonical')
  canonical.setAttribute('href', href)
  if (!canonical.parentNode) document.head.appendChild(canonical)
}

function currentCanonical() {
  return `${window.location.origin}${window.location.pathname}`
}

function renderSeo() {
  if (typeof document === 'undefined') return
  const value = { ...baseSeo, ...pageSeo?.value }
  const description = value.description || baseSeo.description
  if (value.title) document.title = value.title
  setMeta('name', 'description', description)
  setMeta('name', 'keywords', value.keywords)
  setMeta('name', 'robots', value.robots || 'index,follow')
  setMeta('property', 'og:title', value.title)
  setMeta('property', 'og:description', description)
  setMeta('property', 'og:url', value.canonicalUrl || currentCanonical())
  setMeta('property', 'og:type', 'website')
  setMeta('property', 'og:image', value.imageUrl)
  setMeta('name', 'twitter:card', value.imageUrl ? 'summary_large_image' : 'summary')
  setMeta('name', 'twitter:title', value.title)
  setMeta('name', 'twitter:description', description)
  setMeta('name', 'twitter:image', value.imageUrl)
  setCanonical(value.canonicalUrl || currentCanonical())
}

/** 写入路由默认 SEO；页面级覆盖存在时不会被异步公共配置覆盖。 */
export function setBaseSeo(value: PublicSeoMeta) {
  baseSeo = value
  renderSeo()
}

/** 页面详情加载后设置更高优先级的 SEO，并返回清理函数。 */
export function setPageSeo(owner: string, value: PublicSeoMeta) {
  pageSeo = { owner, value }
  renderSeo()
  return () => {
    if (pageSeo?.owner === owner) {
      pageSeo = null
      renderSeo()
    }
  }
}
