type SessionSkillSnapshot = {
  sessionId: string
  activeRun?: { selectedSkillCode?: string | null } | null
}

/** Restore a conversation's last selection once; subsequent events must not undo a local choice. */
export function restoredAssistantSessionSkill(previousSessionId: string | undefined, snapshot: SessionSkillSnapshot): string | undefined {
  if (!snapshot.sessionId || previousSessionId === snapshot.sessionId) return undefined
  if (snapshot.activeRun) return snapshot.activeRun.selectedSkillCode || ''
  // A newly created composer may already have an explicit selection from its entry point.
  // Switching from another conversation must never carry that conversation's selection over.
  return previousSessionId ? '' : undefined
}
