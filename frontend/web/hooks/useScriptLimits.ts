'use client'

import { useEffect, useState } from 'react'
import type { AuthPublicConfigData } from '~/types/business-api'
import { authPublicConfig } from '~/utils/business/auth'

type ScriptLimits = NonNullable<AuthPublicConfigData['scriptLimits']>

export function useScriptLimits(active = true) {
  const [limits, setLimits] = useState<ScriptLimits>()

  useEffect(() => {
    if (!active) return
    let current = true
    void authPublicConfig().then(({ scriptLimits }) => {
      if (current && scriptLimits && [scriptLimits.seriesMaxChars, scriptLimits.episodeMaxChars,
        scriptLimits.movieMaxChars].every((value) => Number.isSafeInteger(value) && value > 0)) {
        setLimits(scriptLimits)
      }
    }).catch(() => {
      if (current) setLimits(undefined)
    })
    return () => { current = false }
  }, [active])

  return limits
}
