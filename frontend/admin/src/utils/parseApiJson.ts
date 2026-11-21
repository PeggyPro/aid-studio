import type { AxiosRequestConfig } from 'axios';

/** Keep 64-bit identifier digits intact while ordinary counts and decimal values retain their types. */
export function parseApiJson<T = unknown>(source: string): T {
  const numberToken = /-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/y;
  const chunks: string[] = [];
  let copiedThrough = 0;
  let inString = false;
  let escaped = false;
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === '"') { inString = true; continue; }
    if (char !== '-' && (char < '0' || char > '9')) continue;
    numberToken.lastIndex = index;
    const match = numberToken.exec(source);
    if (!match) continue;
    const token = match[0];
    if (!/[.eE]/.test(token) && !Number.isSafeInteger(Number(token))) {
      chunks.push(source.slice(copiedThrough, index), JSON.stringify(token));
      copiedThrough = index + token.length;
    }
    index += token.length - 1;
  }
  if (!chunks.length) return JSON.parse(source) as T;
  chunks.push(source.slice(copiedThrough));
  return JSON.parse(chunks.join('')) as T;
}

/** Precede Axios's native transform; keep its text, binary and invalid-JSON behavior unchanged. */
export function preserveApiIntegerResponse(this: AxiosRequestConfig, data: unknown): unknown {
  if (typeof data !== 'string' || !(this.responseType === 'json'
    || !this.responseType && this.transitional?.forcedJSONParsing !== false)) return data;
  try { return parseApiJson(data); }
  catch { return data; }
}
