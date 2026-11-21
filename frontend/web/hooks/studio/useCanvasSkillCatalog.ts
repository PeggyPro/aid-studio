'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { useCanvasAssistantIdentity } from './useCanvasAssistantIdentity'
import type { CanvasRuntimeGroup, CanvasRuntimeMember } from '@/types/canvas-runtime-api'
import { canvasCreatorList, canvasRuntimeGroups, canvasRuntimeMembers } from '@/utils/business/canvasRuntime'
import {
  buildCanvasSkillItems,
  type CanvasOwnSkill,
  type CanvasSkillItem
} from '@/utils/studio/canvasSkillCatalog'
import { canvasAssistantTransport } from '@/utils/canvas/canvasAssistantTransport'

async function allPages<T>(read: (pageNum: number) => Promise<{ data?: T[]; items?: T[]; total: number }>): Promise<T[]> {
  const result: T[] = []
  for (let page = 1; page <= 20; page += 1) {
    const response = await read(page)
    const items = response.data ?? response.items ?? []
    result.push(...items)
    if (result.length >= response.total || items.length === 0) break
  }
  return result
}

export function useCanvasSkillCatalog() {
  const { identity, actorId } = useCanvasAssistantIdentity()
  const [catalog, setCatalog] = useState<{ identity: string; loading: boolean; error: string; groups: CanvasRuntimeGroup[]; items: CanvasSkillItem[] } | null>(null)
  const [favoriteState, setFavoriteState] = useState<{ key: string; values: string[] }>({ key: '', values: [] })
  // Main-site IDs do not identify Canvas actors. Never carry private summaries or
  // favorites across gateways/accounts, including before the next effect runs.
  const storageKey = actorId ? `aid:canvas:skill-favorites:v2:${encodeURIComponent(canvasAssistantTransport.storageNamespace?.() || '')}:${actorId}` : ''
  const generation = useRef(0)

  useEffect(() => {
    if (!storageKey) return
    try {
      const value = JSON.parse(localStorage.getItem(storageKey) || '[]')
      setFavoriteState({ key: storageKey, values: Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [] })
    } catch {
      setFavoriteState({ key: storageKey, values: [] })
    }
  }, [storageKey])

  const reload = useCallback(async () => {
    const token = ++generation.current
    if (!actorId) return
    const current = () => token === generation.current && identity === canvasAssistantTransport.identityKey()
    setCatalog(previous => ({ identity, loading: true, error: '', groups: previous?.identity === identity ? previous.groups : [], items: previous?.identity === identity ? previous.items : [] }))
    try {
      const [nextGroups, nextMine] = await Promise.all([
        allPages<CanvasRuntimeGroup>((pageNum) => canvasRuntimeGroups({ pageNum, pageSize: 100 })),
        allPages<CanvasOwnSkill>((pageNum) => canvasCreatorList<CanvasOwnSkill>('skill', { pageNum, pageSize: 100 }))
      ])
      const groupMembers = await Promise.all(nextGroups.map((group) => allPages<CanvasRuntimeMember>((pageNum) =>
        canvasRuntimeMembers({ groupCode: group.groupCode, pageNum, pageSize: 100 }))))
      if (!current()) return
      setCatalog({ identity, loading: false, error: '', groups: nextGroups, items: buildCanvasSkillItems({ groups: nextGroups, members: groupMembers.flat(), mine: nextMine }) })
    } catch (cause) {
      if (current()) setCatalog(previous => ({ identity, loading: false, error: cause instanceof Error ? cause.message : 'Skill 目录加载失败', groups: previous?.identity === identity ? previous.groups : [], items: previous?.identity === identity ? previous.items : [] }))
    }
  }, [identity, actorId])

  useEffect(() => { void reload(); return () => { generation.current++ } }, [reload])

  const toggleFavorite = useCallback((code: string) => {
    if (!storageKey || !code) return
    setFavoriteState((current) => {
      const values = current.key === storageKey ? current.values : []
      const next = values.includes(code) ? values.filter((item) => item !== code) : [...values, code]
      try { localStorage.setItem(storageKey, JSON.stringify(next)) } catch { /* Storage may be disabled; keep this session usable. */ }
      return { key: storageKey, values: next }
    })
  }, [storageKey])

  const current = actorId && catalog?.identity === identity ? catalog : null
  return { items: current?.items || [], groups: current?.groups || [], favorites: favoriteState.key === storageKey ? favoriteState.values : [], loading: Boolean(actorId && (!current || current.loading)), error: current?.error || '', reload, toggleFavorite }
}
