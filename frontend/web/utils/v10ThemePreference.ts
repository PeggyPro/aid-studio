'use client'

import { useSyncExternalStore } from 'react'

export const V10_THEME_STORAGE_KEY = 'aid-home-theme'
export type V10ThemeId = 'graphite' | 'paper'
const listeners = new Set<() => void>()
let current: V10ThemeId | undefined

export function isV10Theme(value: unknown): value is V10ThemeId {
  return value === 'graphite' || value === 'paper'
}

export function getV10Theme(): V10ThemeId {
  if (current) return current
  if (typeof window === 'undefined') return 'graphite'
  try {
    const stored = window.localStorage.getItem(V10_THEME_STORAGE_KEY)
    current = isV10Theme(stored) ? stored : 'graphite'
  } catch {
    current = 'graphite'
  }
  return current
}

function publish(theme: V10ThemeId) {
  current = theme
  // Canvas maps this preference to its own materials; it never receives V10 CSS attributes.
  if (document.documentElement.hasAttribute('data-v10-theme')) {
    document.documentElement.dataset.v10Theme = theme
  }
  listeners.forEach((listener) => listener())
}

export function setV10Theme(theme: V10ThemeId): void {
  if (!isV10Theme(theme) || typeof window === 'undefined') return
  try {
    window.localStorage.setItem(V10_THEME_STORAGE_KEY, theme)
  } catch {
    // Switching remains usable in memory when browser storage is unavailable.
  }
  publish(theme)
}

function onStorage(event: StorageEvent) {
  if (event.key !== V10_THEME_STORAGE_KEY && event.key !== null) return
  publish(isV10Theme(event.newValue) ? event.newValue : 'graphite')
}

export function subscribeV10Theme(listener: () => void) {
  if (!listeners.size) window.addEventListener('storage', onStorage)
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
    if (!listeners.size) window.removeEventListener('storage', onStorage)
  }
}

export function useV10Theme(): V10ThemeId {
  return useSyncExternalStore(subscribeV10Theme, getV10Theme, () => 'graphite')
}
