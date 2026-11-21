'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useEffect } from 'react'

/** Compatibility route for bookmarks to the retired demonstration pages. */
export default function LegacyUserPage() {
  const router = useRouter()
  useEffect(() => { router.replace('/') }, [router])
  return <Link href="/">返回首页</Link>
}
