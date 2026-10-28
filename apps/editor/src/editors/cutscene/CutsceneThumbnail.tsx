/**
 * A cutscene's picture: its camera's path seen from above, as format samples it (the server's own
 * `CameraPath`), from a dot where it starts to an arrow where it ends; as it is now (unsaved edits too).
 */
import { cutsceneDirector, type CutsceneResult } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { currentText } from '@/minecraft/item/projectItems'
import { useWorkspace } from '@/state/providers'
import { topDown } from './ops'

const PATH = '#4da3ff'
const START = '#e3b341'
/** Points the path is drawn through: enough for a smooth curve this small. */
const POINTS = 48
const LIMIT = 64

/** Each text's path, sampled once: the director compiles the file, which a thumbnail shouldn't do per render. */
const trails = new Map<string, CutsceneResult>()

function trailOf(id: string, text: string): CutsceneResult {
  const key = `${id}\n${text}`
  let trail = trails.get(key)
  if (!trail) {
    try {
      trail = cutsceneDirector(id, text).trail(POINTS)
    } catch {
      trail = { type: 'failed', problems: [] }
    }
    if (trails.size >= LIMIT) trails.clear()
    trails.set(key, trail)
  }
  return trail
}

export function CutsceneThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'cutscene', id)))
  const trail = text === undefined ? null : trailOf(id, text)
  if (trail?.type !== 'trail' || trail.points.length < 2) return <>{fallback}</>
  const stroke = Math.max(1.5, size / 24)
  const points = topDown(trail.points, size, stroke * 3)
  const [x0, y0] = points[0]!
  const [x1, y1] = points[points.length - 1]!
  // The arrow points the way the path last moved (it may hold still at the end).
  const before = points.findLast(([x, y]) => Math.hypot(x - x1, y - y1) > 0.5)
  // A camera that only turns doesn't go anywhere to draw.
  if (!before) return <>{fallback}</>
  const [xp, yp] = before
  const angle = (Math.atan2(y1 - yp, x1 - xp) * 180) / Math.PI
  const head = stroke * 2.5
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <polyline
        points={points.map(([x, y]) => `${x},${y}`).join(' ')}
        fill="none"
        stroke={PATH}
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={x0} cy={y0} r={stroke * 1.5} fill={START} />
      <polygon
        points={`0,0 ${-head * 1.6},${-head} ${-head * 1.6},${head}`}
        fill={PATH}
        transform={`translate(${x1} ${y1}) rotate(${angle})`}
      />
    </svg>
  )
}
