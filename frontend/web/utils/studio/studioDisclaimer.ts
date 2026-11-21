const STORAGE_PREFIX = 'aid-studio-asset-library-disclaimer:'

export function studioDisclaimerStorageKey(userId: string | undefined): string {
  return `${STORAGE_PREFIX}${userId || 'anonymous'}`
}

export function hasSignedStudioDisclaimer(
  storage: Pick<Storage, 'getItem'>,
  userId?: string
): boolean {
  return storage.getItem(studioDisclaimerStorageKey(userId)) === 'accepted'
}

export function signStudioDisclaimer(
  storage: Pick<Storage, 'setItem'>,
  userId?: string
): void {
  storage.setItem(studioDisclaimerStorageKey(userId), 'accepted')
}
