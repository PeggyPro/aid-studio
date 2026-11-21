#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'

const textExtensions = /\.(?:[cm]?[jt]sx?|json|css|less|scss|html|vue|svg|txt|ya?ml|xml|env|example|sh|ps1|bat|cmd|ini|properties|conf|md|gitignore)$/i
const legalName = /(?:^|[.-])(?:licen[cs]e|copying|notice)(?:[.-]|$)/i
const metadataRoots = new Set(['doc', 'docs', 'rules', 'version', '.cursor', '.claude', '.agents', '.codex', '.vscode', '.idea', '.git', 'node_modules', '.next', 'dist', 'out', 'build', 'coverage', 'tests', '.tmp'])

export function publicSourceExclusion(path) {
  const parts = path.split('/'), name = parts.at(-1), lower = path.toLowerCase()
  if (metadataRoots.has(parts[0].toLowerCase())) return 'private-documentation-or-local-output'
  if (/(?:^|\/)(?:__tests__|__mocks__|__snapshots__|fixtures)(?:\/|$)|\.(?:test|spec)\.[^/]+$|\.snap$/i.test(path)) return 'tests'
  if (/^\.env/i.test(name) && name !== '.env.example') return 'environment'
  if (/^(?:agents|claude)\.md$|^\.(?:npmrc|yarnrc)|(?:credentials|secrets?)\.(?:json|ya?ml|ini)$/i.test(name)) return 'private-metadata'
  if (/\.(?:md|mdc|docx?|xlsx?|pdf)$/i.test(name) && !legalName.test(name)) return 'documentation'
  if (/\.(?:pem|key|p12|pfx|bak|dump|sqlite3?|db|log|zip|7z|tar|gz|tsbuildinfo)$/i.test(name)) return 'sensitive-or-generated'
  if (name === 'README.md' || lower === '.gitignore') return 'generated-public-metadata'
  return null
}

const publicReadme = `# Web client\n\nNext.js、React、TypeScript 与 Ant Design Web 客户端。运行时需要兼容的后端服务。\n\n## 开发\n\n需要 Node.js 22.22.0、npm 10.9.4。复制 \`.env.example\` 为 \`.env.local\`，将 \`NEXT_PROXY_TARGET\` 指向自己的后端。\n\n\`\`\`sh\nnvm install 22.22.0\nnvm use 22.22.0\nnpm ci\nnpm run dev\n\`\`\`\n\n## 检查与构建\n\n\`\`\`sh\nnpm run typecheck\nnpm run build\n# 静态站点输出至 dist/public\nnpm run generate\n\`\`\`\n\n开发代理与生产反向代理由部署者配置。模型资源和可选依赖由仓库内 prepare 脚本按配置准备。第三方许可保留在对应资源或组件目录。\n`
const publicIgnore = `node_modules/\n.next/\n.next-*/\nout/\ndist/\nbuild/\ncoverage/\n.env*\n!.env.example\n*.log\n*.tsbuildinfo\nnext-env.d.ts\n.idea/\n.vscode/\n.DS_Store\npublic/vendor/imgly/background-removal/\npublic/studio/emotion/vision/*.wasm.gz\npublic/models/director/official/\n`

function git(repo, args, options = {}) {
  return execFileSync('git', args, { cwd: repo, maxBuffer: 512 * 1024 * 1024, ...options })
}

export function scanPublicSource(entries, buffers, policy) {
  const defaults = [
    { name: 'PrivateKey', regex: '-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----' },
    { name: 'GitHubToken', regex: 'gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,}' },
    { name: 'CloudAccessKey', regex: '\\b(?:AKID[A-Za-z0-9]{13,40}|LTAI[A-Za-z0-9]{12,24})\\b' },
    { name: 'ServiceKey', regex: '\\bsk-[A-Za-z0-9_-]{20,}' }
  ]
  const rules = [...defaults, ...(policy.errorPatterns || [])].map(rule => ({ ...rule, compiled: new RegExp(rule.regex, `${rule.flags || ''}g`) }))
  const hits = []
  for (const entry of entries) {
    if (!textExtensions.test(entry.path) && !['.gitignore', 'Dockerfile'].includes(entry.path)) continue
    const text = buffers.get(entry.oid).toString('utf8')
    for (const rule of rules) {
      rule.compiled.lastIndex = 0
      for (const match of text.matchAll(rule.compiled)) {
        if ((policy.allowMatches || []).some(allow => (allow.patternName === rule.name || allow.patternName === '*') && new RegExp(allow.regex, allow.flags || '').test(match[0]))) continue
        hits.push({ path: entry.path, line: text.slice(0, match.index).split('\n').length, rule: rule.name })
      }
    }
  }
  return hits
}

export function exportPublicSource({ repo, ref, output, auditPolicy, manifest }) {
  repo = resolve(repo)
  output = resolve(output)
  manifest = resolve(manifest)
  const manifestRelative = relative(output, manifest)
  if (!manifestRelative.startsWith(`..${sep}`) && manifestRelative !== '..' && !isAbsolute(manifestRelative)) throw new Error('审计清单必须保存在公开目录之外')
  if (existsSync(output)) throw new Error('输出目录已存在；不会覆盖或清理已有目录')
  const source = git(repo, ['rev-parse', `${ref}^{commit}`], { encoding: 'utf8' }).trim()
  const policy = JSON.parse(readFileSync(auditPolicy, 'utf8'))
  if (!Array.isArray(policy.errorPatterns) || !policy.errorPatterns.length) throw new Error('必须提供已审查且非空的私有审计规则')
  const entries = git(repo, ['ls-tree', '-r', '-z', source]).toString('utf8').split('\0').filter(Boolean).map(line => {
    const tab = line.indexOf('\t'), [mode, type, oid] = line.slice(0, tab).split(' '), path = line.slice(tab + 1)
    if (type !== 'blob' || !['100644', '100755'].includes(mode)) throw new Error(`不支持的源条目：${path}`)
    if (path.split('/').some(part => !part || part === '.' || part === '..') || isAbsolute(path) || path.includes('\\')) throw new Error('源路径越界')
    return { mode, oid, path }
  })
  const excluded = [], selected = []
  for (const entry of entries) {
    const reason = publicSourceExclusion(entry.path)
    if (reason) excluded.push({ ...entry, reason })
    else selected.push(entry)
  }
  const oids = [...new Set(selected.map(entry => entry.oid))]
  const raw = git(repo, ['cat-file', '--batch'], { input: `${oids.join('\n')}\n` })
  const buffers = new Map()
  let cursor = 0
  for (const expected of oids) {
    const end = raw.indexOf(10, cursor), [oid, type, length] = raw.subarray(cursor, end).toString('ascii').split(' ')
    if (oid !== expected || type !== 'blob') throw new Error('Git对象读取不一致')
    const size = Number(length)
    cursor = end + 1
    buffers.set(oid, raw.subarray(cursor, cursor + size))
    cursor += size + 1
  }
  const generated = [
    { path: 'README.md', mode: '100644', data: Buffer.from(publicReadme) },
    { path: '.gitignore', mode: '100644', data: Buffer.from(publicIgnore) }
  ]
  const reviewEntries = [...selected, ...generated.map(entry => ({ ...entry, oid: `generated:${entry.path}` }))]
  for (const entry of generated) buffers.set(`generated:${entry.path}`, entry.data)
  const hits = scanPublicSource(reviewEntries, buffers, policy)
  const report = { source, sourceTree: git(repo, ['rev-parse', `${source}^{tree}`], { encoding: 'utf8' }).trim(), selected, excluded, generated: generated.map(({ path, mode }) => ({ path, mode })), hits }
  mkdirSync(dirname(manifest), { recursive: true })
  if (hits.length) {
    writeFileSync(manifest, JSON.stringify(report, null, 2))
    throw new Error(`秘密扫描阻止导出：${hits.length}项；仅文件/行号/规则已写入私有审计清单`)
  }
  mkdirSync(output, { recursive: true })
  for (const entry of reviewEntries) {
    const destination = resolve(output, ...entry.path.split('/'))
    if (!destination.startsWith(`${output}${sep}`)) throw new Error('目标路径越界')
    mkdirSync(dirname(destination), { recursive: true })
    const data = buffers.get(entry.oid)
    writeFileSync(destination, data, { mode: entry.mode === '100755' ? 0o755 : 0o644 })
    if (!readFileSync(destination).equals(data)) throw new Error(`文件校验失败：${entry.path}`)
  }
  report.codeParity = selected.map(entry => ({ path: entry.path, mode: entry.mode, oid: entry.oid, sha256: createHash('sha256').update(buffers.get(entry.oid)).digest('hex') }))
  writeFileSync(manifest, JSON.stringify(report, null, 2))
  return { source, selected: selected.length, excluded: excluded.length, generated: generated.length, secrets: 0 }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = {}
  for (let i = 2; i < process.argv.length; i += 2) {
    const key = process.argv[i], value = process.argv[i + 1]
    if (!['--repo', '--ref', '--output', '--audit-policy', '--manifest'].includes(key) || !value || value.startsWith('--')) throw new Error('用法：--repo <Git目录> --ref <已提交引用> --output <新目录> --audit-policy <私有规则JSON> --manifest <公开目录外清单>')
    args[key.slice(2)] = value
  }
  if (!args.output || !args['audit-policy'] || !args.manifest) throw new Error('必须指定输出目录、私有审计规则和外部审计清单')
  console.log(JSON.stringify(exportPublicSource({ repo: args.repo || process.cwd(), ref: args.ref || 'HEAD', output: args.output, auditPolicy: args['audit-policy'], manifest: args.manifest })))
}
