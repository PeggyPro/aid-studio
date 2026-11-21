import { readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

// Reproducible local assets: no model changes or third-party runtime downloads.
const directory = join(dirname(dirname(fileURLToPath(import.meta.url))), 'public/studio/emotion/vision')
for (const name of ['vision_wasm_internal', 'vision_wasm_nosimd_internal']) {
  const original = await readFile(join(directory, `${name}.wasm`))
  const compressed = gzipSync(original, { level: 9 })
  const destination = join(directory, `${name}.wasm.gz`)
  const previous = await readFile(destination).catch(() => null)
  if (!previous?.equals(compressed)) await writeFile(destination, compressed)
  console.log(`Prepared emotion WASM ${name}: ${original.length} -> ${compressed.length} bytes`)
}
