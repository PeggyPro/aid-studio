// Default deny: new editor commands must not silently become available to viewers.
const VIEW_ACTIONS = new Set([
  'beginLoad', 'hydrate', 'setLoadError',
  'selectNodes', 'clearSelection', 'applyNodeChanges', 'setViewport',
  'setCanvasTool', 'setMiniMapOpen', 'setHideEdges', 'setReducedMotion',
])

export function guardStudioReadOnlyActions<T extends { readOnly: boolean }>(state: T, get: () => T): T {
  return Object.fromEntries(Object.entries(state).map(([key, value]) => [key,
    typeof value === 'function' && !VIEW_ACTIONS.has(key)
      ? (...args: unknown[]) => get().readOnly ? undefined : Reflect.apply(value, undefined, args)
      : value,
  ])) as T
}
