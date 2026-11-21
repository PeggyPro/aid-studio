/**
 * Preserve integer tokens beyond JavaScript's safe range before JSON.parse rounds them.
 * Canvas IDs are decimal strings by contract, but some gateway responses still emit
 * 64-bit IDs as JSON numbers. Quoting those tokens at the transport boundary keeps
 * their exact digits for the existing string-ID domain model.
 */
export function parseCanvasApiJson<T>(source: string): T {
  const numberToken = /-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/y
  const chunks: string[] = []
  let copiedThrough = 0
  let inString = false
  let escaped = false

  for (let index = 0; index < source.length; index += 1) {
    const char = source[index]
    if (inString) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === '"') inString = false
      continue
    }
    if (char === '"') {
      inString = true
      continue
    }
    if (char !== '-' && (char < '0' || char > '9')) continue

    numberToken.lastIndex = index
    const match = numberToken.exec(source)
    if (!match) continue
    const token = match[0]
    if (!/[.eE]/.test(token) && !Number.isSafeInteger(Number(token))) {
      chunks.push(source.slice(copiedThrough, index), JSON.stringify(token))
      copiedThrough = index + token.length
    }
    index += token.length - 1
  }

  if (chunks.length === 0) return JSON.parse(source) as T
  chunks.push(source.slice(copiedThrough))
  return JSON.parse(chunks.join('')) as T
}
