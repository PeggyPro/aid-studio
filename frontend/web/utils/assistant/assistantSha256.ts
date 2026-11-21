import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'

/** Produce the same lowercase SHA-256 digest on HTTPS and self-hosted HTTP. */
export function assistantSha256(bytes: Uint8Array): string {
  return bytesToHex(sha256(bytes))
}
