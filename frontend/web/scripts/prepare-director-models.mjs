import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const catalog = JSON.parse(await readFile(resolve(root, 'config/directorModels.json'), 'utf8'))
const ids = new Set(), files = new Set()
for (const model of catalog) {
  if (!/^[a-z0-9-]+$/.test(model.id) || !/^[a-z_]+\/[a-z_]+\.glb$/.test(model.file) || ids.has(model.id) || files.has(model.file)) {
    throw new Error('Invalid or duplicate director model catalog entry')
  }
  ids.add(model.id); files.add(model.file)
  const bytes = await readFile(resolve(root, 'models', model.file))
  if (bytes.length < 20 || bytes.readUInt32LE(0) !== 0x46546c67 || bytes.readUInt32LE(4) !== 2 || bytes.readUInt32LE(8) !== bytes.length) {
    throw new Error(`Invalid GLB asset: ${model.file}`)
  }
  const destination = resolve(root, 'public/models/director/official', model.file)
  const previous = await readFile(destination).catch(() => null)
  if (!previous?.equals(bytes)) {
    await mkdir(dirname(destination), { recursive: true })
    await writeFile(destination, bytes)
  }
}
console.log(`Prepared ${catalog.length} official director GLB models`)
