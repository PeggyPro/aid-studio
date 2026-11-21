import type { UserModelListItem } from '@/types/business-api'
import { resolveModelPromptCharacterLimit } from '@/utils/modelCapability'

export const STUDIO_COMPOSER_PROMPT_DEFAULT_MAX_CHARACTERS = 10_000

export function resolveStudioComposerPromptCharacterLimit(
  model: UserModelListItem | null | undefined,
  prompt: string
): number {
  return resolveModelPromptCharacterLimit(
    model,
    prompt,
    STUDIO_COMPOSER_PROMPT_DEFAULT_MAX_CHARACTERS
  )
}

/** 已有内容超过新模型上限时仍允许用户继续删除，直至回到有效范围。 */
export function canApplyStudioComposerPromptChange(
  previousPrompt: string,
  nextPrompt: string,
  resolveLimit: (prompt: string) => number
): boolean {
  return nextPrompt.length <= resolveLimit(nextPrompt)
    || nextPrompt.length < previousPrompt.length
}
