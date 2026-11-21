/* One worker serializes command-journal ownership across tabs of this origin. */
const queues = new Map()

self.onconnect = ({ ports: [port] }) => {
  let name
  const release = () => {
    const queue = queues.get(name)
    if (queue) {
      const index = queue.indexOf(port)
      if (index >= 0) {
        queue.splice(index, 1)
        if (index === 0 && queue.length) queue[0].postMessage('granted')
        if (!queue.length) queues.delete(name)
      }
    }
    port.close()
  }

  port.addEventListener('close', release, { once: true })
  port.onmessageerror = release
  port.onmessage = ({ data }) => {
    if (data === 'release') { release(); return }
    if (name || typeof data?.name !== 'string' || !data.name) return
    name = data.name
    const queue = queues.get(name) ?? []
    if (queue.length && data.ifAvailable) {
      port.postMessage('unavailable')
      return
    }
    if (!queue.length) queues.set(name, queue)
    queue.push(port)
    port.postMessage(queue.length === 1 ? 'granted' : 'waiting')
  }
}
