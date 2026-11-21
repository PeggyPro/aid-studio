/** Classify display purpose from the server projection; preserve unmarked and future output types. */
export function assistantArtifactIsDeliverable(artifact: { presentationRole?: string }): boolean {
  return artifact.presentationRole !== 'INTERNAL_STATE'
}
