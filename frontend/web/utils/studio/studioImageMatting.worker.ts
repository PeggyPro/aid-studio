/// <reference lib="webworker" />

import { removeBackground } from '@imgly/background-removal'

type Request = { id: string; blob: Blob; publicPath: string }
type Response = { id: string; blob?: Blob; error?: string }

const nativeFetch = self.fetch.bind(self)
const imglyFetchQueue: Array<{
  input: RequestInfo | URL
  init?: RequestInit
  resolve: (value: globalThis.Response) => void
  reject: (reason?: unknown) => void
}> = []
let activeImglyFetches = 0
const MAX_IMGLY_FETCHES = 4

function drainImglyFetchQueue() {
  while (activeImglyFetches < MAX_IMGLY_FETCHES && imglyFetchQueue.length) {
    const queued = imglyFetchQueue.shift()!
    activeImglyFetches += 1
    nativeFetch(queued.input, queued.init)
      .then(queued.resolve, queued.reject)
      .finally(() => {
        activeImglyFetches -= 1
        drainImglyFetchQueue()
      })
  }
}

/** IMG.LY requests every model chunk at once; cap native requests for ordinary HTTP/2 servers. */
function boundedFetch(input: RequestInfo | URL, init?: RequestInit): Promise<globalThis.Response> {
  const href = input instanceof Request ? input.url : String(input)
  if (!href.includes('/vendor/imgly/background-removal/')) return nativeFetch(input, init)
  return new Promise((resolve, reject) => {
    imglyFetchQueue.push({ input, init, resolve, reject })
    drainImglyFetchQueue()
  })
}

self.fetch = boundedFetch as typeof fetch

self.onmessage = async (event: MessageEvent<Request>) => {
  const { id, blob, publicPath } = event.data
  try {
    const result = await removeBackground(blob, {
      publicPath,
      model: 'isnet',
      device: 'gpu',
      proxyToWorker: false,
      output: { format: 'image/png', quality: 1 }
    })
    self.postMessage({ id, blob: result } satisfies Response)
  } catch (error: unknown) {
    self.postMessage({ id, error: String((error as Error)?.message || error || '\u62a0\u56fe\u5931\u8d25') } satisfies Response)
  }
}

export {}
