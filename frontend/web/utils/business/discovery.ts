/** 发现广场：公开列表/详情与登录用户投稿。 */
import type {
  ApiEnvelope,
  PublicDiscoveryDetailRequest,
  PublicDiscoveryItem,
  PublicDiscoveryPageRequest,
  UserDiscoveryDeleteRequest,
  UserDiscoveryDetailRequest,
  UserDiscoveryItem,
  UserDiscoveryMyPageRequest,
  UserDiscoveryResubmitRequest,
  UserDiscoverySubmitResult,
  UserDiscoverySubmitRequest
} from '~/types/business-api'
import { request } from '~/utils/api'
import {
  parseDiscoveryPageAjaxResult,
  type DiscoveryPageAjaxResult
} from '~/utils/discoveryPageContract'
import {
  runListDedupe,
  stableRequestKey,
  unwrap,
  type ListBurstSlot
} from '~/utils/business/shared'

interface DiscoveryPageData<Item> {
  total: number
  data: Item[]
}

const publicPageInflight = new Map<string, Promise<DiscoveryPageData<PublicDiscoveryItem>>>()
const publicPageBurst: ListBurstSlot<DiscoveryPageData<PublicDiscoveryItem>> = { current: null }
const userPageInflight = new Map<string, Promise<DiscoveryPageData<UserDiscoveryItem>>>()
const userPageBurst: ListBurstSlot<DiscoveryPageData<UserDiscoveryItem>> = { current: null }

async function invalidateUserDiscoveryPageCache(): Promise<void> {
  const staleRequests = Array.from(userPageInflight.values())
  if (staleRequests.length > 0) await Promise.allSettled(staleRequests)
  userPageBurst.current = null
}

export async function publicDiscoveryPage(
  body: PublicDiscoveryPageRequest
): Promise<DiscoveryPageData<PublicDiscoveryItem>> {
  const key = stableRequestKey(body)
  return runListDedupe(key, publicPageInflight, publicPageBurst, async () => {
    const res = await request.post<DiscoveryPageAjaxResult<PublicDiscoveryItem>>(
      '/api/public/discovery/page',
      body
    )
    return parseDiscoveryPageAjaxResult(res)
  })
}

const publicDetailInflight = new Map<number, Promise<PublicDiscoveryItem>>()

export async function publicDiscoveryDetail(
  body: PublicDiscoveryDetailRequest
): Promise<PublicDiscoveryItem> {
  const existing = publicDetailInflight.get(body.id)
  if (existing) return existing
  const pending = request
    .post<ApiEnvelope<PublicDiscoveryItem>>('/api/public/discovery/detail', body)
    .then(unwrap)
    .finally(() => publicDetailInflight.delete(body.id))
  publicDetailInflight.set(body.id, pending)
  return pending
}

export async function userDiscoverySubmit(
  body: UserDiscoverySubmitRequest
): Promise<UserDiscoverySubmitResult> {
  const res = await request.post<ApiEnvelope<UserDiscoverySubmitResult>>(
    '/api/user/discovery/submit',
    body
  )
  const data = unwrap(res)
  await invalidateUserDiscoveryPageCache()
  return data
}

export async function userDiscoveryMyPage(
  body: UserDiscoveryMyPageRequest
): Promise<DiscoveryPageData<UserDiscoveryItem>> {
  const key = stableRequestKey(body)
  return runListDedupe(key, userPageInflight, userPageBurst, async () => {
    const res = await request.post<DiscoveryPageAjaxResult<UserDiscoveryItem>>(
      '/api/user/discovery/my/page',
      body
    )
    return parseDiscoveryPageAjaxResult(res)
  })
}

const userDetailInflight = new Map<number, Promise<UserDiscoveryItem>>()

export async function userDiscoveryDetail(
  body: UserDiscoveryDetailRequest
): Promise<UserDiscoveryItem> {
  const existing = userDetailInflight.get(body.id)
  if (existing) return existing
  const pending = request
    .post<ApiEnvelope<UserDiscoveryItem>>('/api/user/discovery/my/detail', body)
    .then(unwrap)
    .finally(() => userDetailInflight.delete(body.id))
  userDetailInflight.set(body.id, pending)
  return pending
}

export async function userDiscoveryResubmit(body: UserDiscoveryResubmitRequest): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/discovery/resubmit', body)
  await invalidateUserDiscoveryPageCache()
}

export async function userDiscoveryDelete(body: UserDiscoveryDeleteRequest): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/discovery/delete', body)
  await invalidateUserDiscoveryPageCache()
}
