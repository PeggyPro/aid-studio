/** 静态导出要求动态路由至少声明一个参数；真实 ID 由 200 fallback 转为 /discovery/?id=。 */
export function generateStaticParams() {
  return process.env.NEXT_STATIC_EXPORT === '1' ? [{ id: '__static__' }] : []
}

export default function DiscoveryDynamicLayout({ children }: { children: React.ReactNode }) {
  return children
}
