import { cp } from 'node:fs/promises'
import { prepareNodeRuntime } from './node-runtime.mjs'
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import {
  flattenStaticExportRscTree,
  resolveFlattenedStaticExportRscPath
} from './staticExportRscPaths.mjs'

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)))
const nextBin = join(projectRoot, 'node_modules', 'next', 'dist', 'bin', 'next')
const exportDir = join(projectRoot, 'out')
const publicDir = join(projectRoot, 'dist', 'public')

async function main() {
  const build = spawnSync(process.execPath, [nextBin, 'build', '--webpack'], {
    cwd: projectRoot,
    env: prepareNodeRuntime(projectRoot, { ...process.env, NEXT_STATIC_EXPORT: '1' }),
    stdio: 'pipe',
    encoding: 'utf8'
  })

  if (build.stdout) process.stdout.write(build.stdout)
  if (build.stderr) process.stderr.write(build.stderr)

  const exportIndexPath = join(exportDir, 'index.html')

  if (build.error) throw build.error
  if (build.status !== 0) {
    process.exit(build.status ?? 1)
  }
  if (!existsSync(exportIndexPath)) {
    throw new Error('Next 静态导出缺少 out/index.html')
  }

  // Next 16 Windows 静态导出可能留下嵌套 __next 段目录；压平为客户端请求的扁平文件名。
  const { renamed } = await flattenStaticExportRscTree(exportDir)
  if (renamed > 0) {
    console.log(`Normalized ${renamed} nested static-export RSC segment file(s)`)
  }

  function assertNoNestedStaticExportRsc(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const full = join(directory, entry.name)
      if (entry.isDirectory()) {
        if (entry.name.startsWith('__next')) {
          throw new Error(`静态导出仍含嵌套 RSC 段目录（应已压平）: ${full}`)
        }
        assertNoNestedStaticExportRsc(full)
        continue
      }
      if (entry.isFile() && resolveFlattenedStaticExportRscPath(full)) {
        throw new Error(`静态导出仍含嵌套 RSC 段文件（应已压平）: ${full}`)
      }
    }
  }
  assertNoNestedStaticExportRsc(exportDir)

  // Reject incomplete static output even when a stale export directory exists.
  function verifyRoutes(directory, segments = []) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.isDirectory() && !entry.name.startsWith('[') && !entry.name.startsWith('@') && !entry.name.startsWith('_')) {
        verifyRoutes(join(directory, entry.name), entry.name.startsWith('(') ? segments : [...segments, entry.name])
      } else if (entry.isFile() && /^page\.(?:tsx|ts|jsx|js)$/.test(entry.name)) {
        const route = segments.join('/')
        if (!existsSync(join(exportDir, route, 'index.html')) && !existsSync(join(exportDir, `${route}.html`))) {
          throw new Error(`静态导出缺少公开路由文件: /${route}`)
        }
      }
    }
  }
  verifyRoutes(join(projectRoot, 'app'))

  rmSync(publicDir, { recursive: true, force: true })
  mkdirSync(publicDir, { recursive: true })
  await cp(exportDir, publicDir, { recursive: true, force: true })

  const notFoundPath = join(publicDir, '404.html')
  const fallbackSource = existsSync(notFoundPath)
    ? readFileSync(notFoundPath, 'utf8')
    : readFileSync(join(publicDir, 'index.html'), 'utf8')
  const legacyDynamicRouteRedirect = `<script>(function(){var p=location.pathname.replace(/\\/+$/,'');var m=p.match(/^\\/case\\/([^/]+)$/);var target='';if(m){var q=new URLSearchParams(location.search);q.set('id',decodeURIComponent(m[1]));target='/case/?'+q.toString()+location.hash}else{m=p.match(/^\\/user\\/([^/]+)$/);if(m){var u=new URLSearchParams(location.search);u.set('id',decodeURIComponent(m[1]));target='/user/?'+u.toString()+location.hash}}if(target)location.replace(target)})();</script>`
  const discoveryDynamicRouteRedirect = '<script>(function(){var p=location.pathname.replace(/\\/+$/,""),m=p.match(/^\\/discovery\\/([^/]+)$/);if(!m)return;var q=new URLSearchParams(location.search);q.set("id",decodeURIComponent(m[1]));location.replace("/discovery/?"+q.toString()+location.hash)})();</script>'
  const dynamicRouteRedirects = legacyDynamicRouteRedirect + discoveryDynamicRouteRedirect
  const fallbackHtml = fallbackSource.includes('<head>')
    ? fallbackSource.replace('<head>', '<head>' + dynamicRouteRedirects)
    : dynamicRouteRedirects + fallbackSource

  writeFileSync(join(publicDir, '200.html'), fallbackHtml, 'utf8')

  console.log(`\nStatic release generated: ${publicDir}`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
