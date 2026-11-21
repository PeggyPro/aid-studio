type NodePoint = { x: number; y: number }

function hashSeed(value: string) {
  let seed = 17
  for (let index = 0; index < value.length; index += 1) {
    seed = (seed * 33 + value.charCodeAt(index)) % 233280
  }
  return seed || 17
}

function nodeLayout(seedKey: string) {
  let seed = hashSeed(seedKey)
  const rand = (span: number) => {
    seed = (seed * 9301 + 49297) % 233280
    return (seed / 233280) * span
  }
  const a: NodePoint = { x: 22 + rand(20), y: 18 + rand(16) }
  const b: NodePoint = { x: 24 + rand(24), y: 54 + rand(10) }
  const c: NodePoint = { x: 150 + rand(40), y: 30 + rand(20) }
  const wire = (from: NodePoint, offsetX: number, offsetY: number) =>
    `M${from.x + offsetX} ${from.y + offsetY} C ${from.x + 90} ${from.y + offsetY}, ${c.x - 40} ${c.y + 16}, ${c.x} ${c.y + 16}`
  const duration = (2.4 + rand(1.2)).toFixed(2)
  const clipKey = seedKey.replace(/[^a-zA-Z0-9]/g, '').slice(-12) || 'canvas'
  return {
    a,
    b,
    c,
    w1: wire(a, 44, 12),
    w2: wire(b, 44, 10),
    duration,
    clipId: `home-node-${clipKey}-${seed}`
  }
}

export function HomeCanvasNodeThumb({
  seedKey,
  image
}: {
  seedKey: string
  image?: string | null
}) {
  const layout = nodeLayout(seedKey)
  return (
    <svg viewBox="0 0 260 92" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <path className="wr" d={layout.w1} />
      <path className="wr" d={layout.w2} />
      <circle className="pulse" r="1.8">
        <animateMotion dur={`${layout.duration}s`} repeatCount="indefinite" path={layout.w1} />
      </circle>
      <circle className="pulse" r="1.8">
        <animateMotion
          dur={`${layout.duration}s`}
          begin={`-${(Number(layout.duration) / 2).toFixed(2)}s`}
          repeatCount="indefinite"
          path={layout.w2}
        />
      </circle>
      <g className="g1">
        <rect className="nd" x={layout.a.x} y={layout.a.y} width="44" height="24" rx="5" />
      </g>
      <g className="g1">
        <rect className="nd" x={layout.b.x} y={layout.b.y} width="44" height="20" rx="5" />
      </g>
      <g className="g2 im">
        <clipPath id={layout.clipId}>
          <rect x={layout.c.x} y={layout.c.y} width="58" height="34" rx="5" />
        </clipPath>
        {image ? (
          <image
            href={image}
            x={layout.c.x}
            y={layout.c.y}
            width="58"
            height="34"
            preserveAspectRatio="xMidYMid slice"
            clipPath={`url(#${layout.clipId})`}
            opacity="0.85"
          />
        ) : (
          <rect className="nd" x={layout.c.x} y={layout.c.y} width="58" height="34" rx="5" />
        )}
        <rect className="frame" x={layout.c.x} y={layout.c.y} width="58" height="34" rx="5" />
      </g>
    </svg>
  )
}
