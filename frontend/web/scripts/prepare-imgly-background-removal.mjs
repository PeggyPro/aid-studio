import { createHash } from 'node:crypto'
import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'

const version = '1.7.0'
const sourceBase = `https://staticimgly.com/@imgly/background-removal-data/${version}/dist/`
const destination = path.resolve('public', 'vendor', 'imgly', 'background-removal', version)
const selectedKeys = new Set([
  '/onnxruntime-web/ort-wasm-simd-threaded.jsep.wasm',
  '/onnxruntime-web/ort-wasm-simd-threaded.wasm',
  '/onnxruntime-web/ort-wasm-simd-threaded.jsep.mjs',
  '/onnxruntime-web/ort-wasm-simd-threaded.mjs',
  '/models/isnet'
])

async function fetchOk(url) {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`IMG.LY resource request failed (${response.status}): ${url}`)
  return response
}

async function sha256(file) {
  return createHash('sha256').update(await readFile(file)).digest('hex')
}

async function downloadChunk(chunk) {
  const file = path.join(destination, chunk.name)
  const expectedSize = Number(chunk.offsets?.[1]) - Number(chunk.offsets?.[0])
  try {
    const current = await stat(file)
    if (current.isFile() && current.size === expectedSize && await sha256(file) === chunk.hash) return
  } catch { /* missing or invalid file is downloaded below */ }
  const bytes = Buffer.from(await (await fetchOk(sourceBase + chunk.name)).arrayBuffer())
  if (bytes.length !== expectedSize) throw new Error(`IMG.LY chunk size mismatch: ${chunk.name}`)
  const digest = createHash('sha256').update(bytes).digest('hex')
  if (digest !== chunk.hash) throw new Error(`IMG.LY chunk checksum mismatch: ${chunk.name}`)
  const temporary = `${file}.${process.pid}.tmp`
  await writeFile(temporary, bytes)
  await rename(temporary, file)
}

async function runPool(items, concurrency, handler) {
  let cursor = 0
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length) {
      const item = items[cursor++]
      await handler(item)
    }
  }))
}

await mkdir(destination, { recursive: true })
const manifest = await (await fetchOk(sourceBase + 'resources.json')).json()
const selected = Object.fromEntries(Object.entries(manifest).filter(([key]) => selectedKeys.has(key)))
if (Object.keys(selected).length !== selectedKeys.size) throw new Error('IMG.LY resource manifest is incomplete')
const chunks = [...new Map(Object.values(selected)
  .flatMap((entry) => entry.chunks || [])
  .map((chunk) => [chunk.name, chunk])).values()]
await runPool(chunks, 6, downloadChunk)
const manifestFile = path.join(destination, 'resources.json')
const temporaryManifest = `${manifestFile}.${process.pid}.tmp`
await writeFile(temporaryManifest, JSON.stringify(selected, null, 2) + '\n', 'utf8')
await rename(temporaryManifest, manifestFile)
console.log(`Prepared IMG.LY ${version} isnet assets (${chunks.length} verified chunks)`)
