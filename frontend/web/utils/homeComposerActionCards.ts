export type HomeComposerActionKind = 'movie' | 'series' | 'studio' | 'tutorial'

export interface HomeComposerActionCardDef {
  kind: HomeComposerActionKind
  title: string
  imageFile: 'moive.png' | 'series.png' | 'studio.png' | 'study.png'
  comingSoon?: boolean
  comingSoonLabel?: string
  onboardingId?: string
}

export function homeComposerActionClickTarget(
  kind: HomeComposerActionKind
): HomeComposerActionKind {
  return kind
}

export const HOME_COMPOSER_ACTION_CARDS: readonly HomeComposerActionCardDef[] = [
  { kind: 'movie', title: '制作大片', imageFile: 'moive.png' },
  { kind: 'series', title: '制作连续剧', imageFile: 'series.png' },
  {
    kind: 'tutorial',
    title: '新手教程',
    imageFile: 'study.png',
    onboardingId: 'nav-tutorial'
  },
  {
    kind: 'studio',
    title: '无限画布',
    imageFile: 'studio.png'
  }
]
