type EmscriptenModuleHost = {
  Module?: {
    print?: (...args: unknown[]) => void
    printErr?: (...args: unknown[]) => void
    [key: string]: unknown
  }
  process?: { versions?: { node?: string }; type?: string }
}

function host(): EmscriptenModuleHost {
  return globalThis as EmscriptenModuleHost
}

function writeVisionLog(...args: unknown[]) {
  console.debug(...args)
}

/**
 * MediaPipe's hosted WASM loader treats `process.versions.node` as "running in
 * Node" unless `process.type === 'renderer'`, and writes C++ diagnostics to
 * stderr / `console.error`. Next.js exposes that Node version in the browser,
 * so free-canvas face detection surfaces TensorFlow INFO/WARN lines as
 * unhandled page errors. Mark the page as a renderer and attach Emscripten
 * Module print hooks before `ModuleFactory` runs.
 */
export function prepareStudioVisionBrowserRuntime() {
  if (typeof window === 'undefined') return
  const processLike = host().process
  if (processLike) {
    try {
      processLike.type = 'renderer'
    } catch {
      // Frozen process shims must not fail face detection.
    }
  }
  try {
    const current = host().Module ?? {}
    host().Module = {
      ...current,
      print: current.print ?? writeVisionLog,
      printErr: current.printErr ?? writeVisionLog
    }
  } catch {
    // Frozen Module hosts still get renderer mode above.
  }
}
