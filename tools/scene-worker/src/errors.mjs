export class BuildError extends Error {
  constructor(code, message, { retryable = false, details = undefined } = {}) {
    super(message); this.name = 'BuildError'; this.code = code;
    this.retryable = retryable; this.details = details;
  }
}

// Only a confirmed failure may be retried. A lost worker lease is not evidence
// that the original execution stopped, so it never authorizes a second build.
export function retryDecision({ state, code, attempt, maxAttempts }) {
  if (state !== 'FAILED') return { allowed: false, reason: 'RECONCILE_OR_WAIT' };
  if (!Number.isInteger(attempt) || !Number.isInteger(maxAttempts) || attempt < 1 || maxAttempts < 1)
    return { allowed: false, reason: 'INVALID_RETRY_POLICY' };
  if (attempt >= maxAttempts) return { allowed: false, reason: 'ATTEMPTS_EXHAUSTED' };
  if (!['ARTIFACT_UPLOAD_UNAVAILABLE', 'WORKER_START_UNAVAILABLE'].includes(code))
    return { allowed: false, reason: 'REQUIRES_CORRECTED_INPUT_OR_CONFIGURATION' };
  return { allowed: true, reason: code === 'ARTIFACT_UPLOAD_UNAVAILABLE' ? 'RETRY_UPLOAD_ONLY' : 'RETRY_START' };
}
