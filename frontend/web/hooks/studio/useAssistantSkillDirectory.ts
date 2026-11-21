'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { useCanvasAssistantIdentity } from './useCanvasAssistantIdentity'
import { canvasAssistantTransport } from '@/utils/canvas/canvasAssistantTransport'
import { canvasApiReadPost } from '@/utils/canvas/canvasApiClient'
import { unwrap } from '@/utils/business/shared'
import type { CanvasSkillItem } from '@/utils/studio/canvasSkillCatalog'
import type { CanvasRuntimeGroup } from '@/types/canvas-runtime-api'

type Entry = { code: string; name: string; description?: string; iconUrl?: string; ownerType: string; visibility: 'PRIVATE' | 'PUBLIC'; outputKind?: CanvasSkillItem['outputKind'] }
type Page = { items: Entry[]; pageNum: number; pageSize: number; hasMore: boolean }
type Query = { keyword?: string; scope?: 'OFFICIAL' | 'OWNED'; pageNum?: number; code?: string; favoriteCodes?: string[] }
function read(query: Query): Promise<Page> {
  const { favoriteCodes, ...parameters } = query
  const page = query.pageNum || 1
  const codes = favoriteCodes?.slice((page - 1) * 20, page * 20)
  if (codes && !codes.length) return Promise.resolve({ items: [], pageNum: page, pageSize: 20, hasMore: false })
  const body = { transport: 'CANVAS', moduleCode: 'UNIVERSAL', pageSize: 20, pageNum: 1, scope: 'OFFICIAL', ...parameters,
    ...(codes ? { codes, pageNum: 1 } : {}) }
  return canvasApiReadPost<Page>('/api/user/assistant/skills/page', body).then(unwrap).then(result => favoriteCodes
    ? { ...result, pageNum: page, hasMore: favoriteCodes.length > page * 20 } : result)
}
function item(entry: Entry): CanvasSkillItem {
  return { code: entry.code, name: entry.name, description: entry.description || '', iconUrl: entry.iconUrl || '',
    own: entry.ownerType === 'USER', group: entry.ownerType === 'USER' ? '我的' : '官方', groupCode: '',
    available: true, visibility: entry.visibility, outputKind: entry.outputKind || 'TEXT' }
}
const general: CanvasSkillItem = { code: '', name: '通用创作', description: '不附加 Skill', group: '', groupCode: '', own: false, available: true, outputKind: 'TEXT', iconUrl: '' }

/** The assistant directory is explicitly opened/searched; mounting never enumerates Skills. */
export function useAssistantSkillDirectory() {
  const { identity, actorId } = useCanvasAssistantIdentity()
  const [state, setState] = useState({ identity: '', items: [] as CanvasSkillItem[], loading: false, error: '', hasMore: false, page: 0 })
  const [favoriteState, setFavoriteState] = useState({ key: '', values: [] as string[] })
  const query = useRef<Query>({})
  const failedRead = useRef<{ query: Query; append: boolean } | null>(null)
  const generation = useRef(0)
  const key = actorId ? `aid:canvas:skill-favorites:v2:${encodeURIComponent(canvasAssistantTransport.storageNamespace?.() || '')}:${actorId}` : ''
  // Favorites decorate the current page. They are not a new directory query.
  const favoritesRef = useRef(favoriteState)
  favoritesRef.current = favoriteState
  useEffect(() => {
    generation.current++
    query.current = {}
    failedRead.current = null
    if (!key) return
    try { const values: unknown = JSON.parse(localStorage.getItem(key) || '[]'); setFavoriteState({ key, values: Array.isArray(values) ? values.filter((v): v is string => typeof v === 'string') : [] }) }
    catch { setFavoriteState({ key, values: [] }) }
    return () => { generation.current++ }
  }, [identity, key])
  const load = useCallback(async (next: Query, append = false) => {
    if (!actorId) return
    const serial = ++generation.current
    query.current = next
    failedRead.current = null
    setState(old => ({ ...old, identity, items: append && old.identity === identity ? old.items : [], loading: true, error: '' }))
    try {
      const page = await read(next)
      if (serial !== generation.current || identity !== canvasAssistantTransport.identityKey()) return
      setState(old => ({ identity, items: [...new Map([...(append && old.identity === identity ? old.items : []), ...page.items.map(item)].map(v => [v.code, v])).values()], loading: false, error: '', hasMore: page.hasMore, page: page.pageNum }))
    } catch (error) {
      if (serial === generation.current && identity === canvasAssistantTransport.identityKey()) {
        failedRead.current = { query: next, append }
        setState(old => ({ ...old, loading: false, error: error instanceof Error ? error.message : 'Skill目录读取失败' }))
      }
    }
  }, [actorId, identity])
  const reload = useCallback(() => {
    const failed = failedRead.current
    return failed ? load(failed.query, failed.append) : load({ ...query.current, pageNum: 1 })
  }, [load])
  const search = useCallback((keyword: string, scope: 'OFFICIAL' | 'OWNED' | 'FAVORITES' = 'OFFICIAL') => load({
    keyword: keyword.trim(), scope: scope === 'FAVORITES' ? 'OFFICIAL' : scope, pageNum: 1,
    ...(scope === 'FAVORITES' ? { favoriteCodes: favoritesRef.current.key === key ? [...favoritesRef.current.values] : [] } : {})
  }), [load, key])
  const loadMore = useCallback(() => state.loading || !state.hasMore ? Promise.resolve() : load({ ...query.current, pageNum: state.page + 1 }, true), [load, state.loading, state.hasMore, state.page])
  const resolveCode = useCallback(async (code: string) => {
    if (!actorId || !code) return null
    const page = await read({ code })
    if (identity !== canvasAssistantTransport.identityKey()) return null
    return page.items[0] ? item(page.items[0]) : null
  }, [actorId, identity])
  const toggleFavorite = useCallback((code: string) => {
    if (!key || !code) return
    setFavoriteState(old => {
      const values = old.key === key ? old.values : []
      const next = values.includes(code) ? values.filter(v => v !== code) : [...values, code]
      try { localStorage.setItem(key, JSON.stringify(next)) } catch { /* Session favorites remain usable. */ }
      return { key, values: next }
    })
  }, [key])
  const current = state.identity === identity ? state : null
  return { items: [general, ...(current?.items || [])], groups: [] as CanvasRuntimeGroup[], favorites: favoriteState.key === key ? favoriteState.values : [],
    loading: current?.loading || false, error: current?.error || '', hasMore: current?.hasMore || false,
    reload, search, loadMore, resolveCode, toggleFavorite }
}
