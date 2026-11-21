'use client'

/**
 * 「我的作品」卡片发布入口跳转引导（query.publishGuide=1，原 CreateFlowShell.vue 引导段拆分）：
 * - 已完成（落在成品预览）：呼吸灯高亮「导出/发布」按钮
 * - 未完成（落在中间步骤）：呼吸灯 + tooltip 高亮当前流程 tab（推进到哪一步就亮哪一步）
 */

import { useCallback,useEffect,useRef,useState } from 'react'
import { useRouteLikeNavigator } from '~/composables/useRouteLike'
import type { RouteLikeLocation } from '~/types/routeLike'

export function useCreateFlowPublishGuide(options: {
  route: RouteLikeLocation
  pageReady: boolean
  isPreviewStep: boolean
  exportMenuOpen: boolean
}) {
  const { route, pageReady, isPreviewStep, exportMenuOpen } = options
  const navigator = useRouteLikeNavigator()

  const [publishGuideActive, setPublishGuideActive] = useState(false)
  const [publishGuideTooltipOpen, setPublishGuideTooltipOpen] = useState(false)
  const publishGuideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const routeRef = useRef(route)
  routeRef.current = route
  const isPreviewStepRef = useRef(isPreviewStep)
  isPreviewStepRef.current = isPreviewStep

  const clearPublishGuide = useCallback(() => {
    setPublishGuideActive(false)
    setPublishGuideTooltipOpen(false)
    if (publishGuideTimerRef.current) {
      clearTimeout(publishGuideTimerRef.current)
      publishGuideTimerRef.current = null
    }
  }, [])

  const activatePublishGuide = useCallback(() => {
    setPublishGuideActive(true)
    // 未完成：tooltip 挂在当前流程 tab；已完成预览步仅呼吸灯「导出/发布」
    setPublishGuideTooltipOpen(!isPreviewStepRef.current)
    if (publishGuideTimerRef.current) clearTimeout(publishGuideTimerRef.current)
    publishGuideTimerRef.current = setTimeout(() => {
      clearPublishGuide()
    }, 10000)

    // 消费掉 publishGuide 参数，避免刷新/回退重复触发
    const r = routeRef.current
    const nextQuery: Record<string, string> = {}
    for (const [key, value] of Object.entries(r.query)) {
      if (key === 'publishGuide' || value == null) continue
      nextQuery[key] = Array.isArray(value) ? String(value[0] ?? '') : String(value)
    }
    void navigator.replace({ path: r.path, query: nextQuery })
  }, [clearPublishGuide, navigator])

  // 原 watch([route.query.publishGuide, pageReady], immediate)
  const guideParam = String(route.query.publishGuide ?? '')
  useEffect(() => {
    if (!pageReady || guideParam !== '1') return
    const t = setTimeout(() => activatePublishGuide(), 0)
    return () => clearTimeout(t)
  }, [guideParam, pageReady, activatePublishGuide])

  /** 打开导出下拉即视为引导完成 */
  useEffect(() => {
    if (exportMenuOpen) clearPublishGuide()
  }, [exportMenuOpen, clearPublishGuide])

  // 原 onBeforeUnmount
  useEffect(() => {
    return () => {
      if (publishGuideTimerRef.current) {
        clearTimeout(publishGuideTimerRef.current)
        publishGuideTimerRef.current = null
      }
    }
  }, [])

  return {
    publishGuideActive,
    publishGuideTooltipOpen,
    clearPublishGuide
  }
}
