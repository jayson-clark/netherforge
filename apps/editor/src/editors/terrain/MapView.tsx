/**
 * The terrain from above, as big as its panel: the scheduler's tiles drawn on one canvas, so panning and zooming
 * move pictures already made (every frame) while the workers draw what's newly in view, coarse first. Drag pans,
 * the wheel (or a pinch) zooms about the pointer, a click cuts the slice there, and the arrow keys, + and - do
 * the same from the keyboard. Where it looks is written to the view once a gesture ends, so a tab keeps it.
 */
import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react'
import { areaColor, decorationColor, place } from '@netherforge/terrain-preview'
import { Muted } from '@/ui/text'
import {
  MAX_STEP,
  MIN_STEP,
  TILE,
  clampZoom,
  stepFor,
  tileKey,
  tilesIn,
  viewBounds,
  wantedTiles,
  type MapCamera,
  type Tile,
  type TileScheduler,
  type Viewport,
} from './tiles'
import styles from './TerrainEditor.module.css'

export interface SliceLine {
  alongX: boolean
  at: number
  along: number
  width: number
}

const BACKGROUND = '#0e1014'
/** How long after the last wheel turn where the map looks is written to the view. */
const COMMIT_MS = 400

const sameCamera = (a: MapCamera, b: MapCamera) => a.x === b.x && a.z === b.z && a.zoom === b.zoom

/** A tile's pixels as something a canvas draws, made once per tile. */
const images = new WeakMap<Tile, HTMLCanvasElement>()
function imageOf(tile: Tile): HTMLCanvasElement {
  let image = images.get(tile)
  if (!image) {
    image = document.createElement('canvas')
    image.width = tile.cells
    image.height = tile.cells
    image
      .getContext('2d')
      ?.putImageData(
        new ImageData(tile.pixels as Uint8ClampedArray<ArrayBuffer>, tile.cells, tile.cells),
        0,
        0,
      )
    images.set(tile, image)
  }
  return image
}

/** A length of blocks that's a round number and about 100 pixels long at [zoom]. */
function scaleBar(zoom: number): { blocks: number; pixels: number } {
  const want = 100 / zoom
  const power = 10 ** Math.floor(Math.log10(want))
  const blocks = [1, 2, 5, 10].map((it) => it * power).find((it) => it >= want) ?? want
  const whole = Math.max(1, Math.round(blocks))
  return { blocks: whole, pixels: whole * zoom }
}

export const MapView = memo(function MapView({
  scheduler,
  camera,
  detail,
  onCamera,
  slice,
  onCut,
}: {
  scheduler: TileScheduler
  /** Where the view says the map looks (adopted when it changes from elsewhere). */
  camera: MapCamera
  detail: number
  onCamera: (camera: MapCamera) => void
  slice: SliceLine
  onCut: (alongX: boolean, at: number, along: number) => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  // Where the map looks as it moves (every pointer event), what was drawn last, and what the view was told.
  const cam = useRef<MapCamera>(camera)
  const committed = useRef<MapCamera>(camera)
  const [view, setView] = useState<MapCamera>(camera)
  const [size, setSize] = useState({ width: 0, height: 0 })
  const [hover, setHover] = useState<{ x: number; z: number } | null>(null)
  const version = useSyncExternalStore(scheduler.subscribe, () => scheduler.version)
  const frame = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const onCameraRef = useRef(onCamera)
  const drag = useRef<{ px: number; py: number; from: MapCamera; moved: boolean } | null>(null)
  const wanted = useRef('')

  useEffect(() => {
    onCameraRef.current = onCamera
  }, [onCamera])

  /** Draws the camera as it is now on the next frame. */
  const schedule = useCallback(() => {
    if (frame.current) return
    frame.current = requestAnimationFrame(() => {
      frame.current = 0
      setView({ ...cam.current })
    })
  }, [])

  const commit = useCallback(() => {
    clearTimeout(timer.current)
    const c = cam.current
    const rounded = { x: Math.round(c.x), z: Math.round(c.z), zoom: c.zoom }
    if (sameCamera(rounded, committed.current)) return
    committed.current = rounded
    onCameraRef.current(rounded)
  }, [])

  const commitSoon = useCallback(() => {
    clearTimeout(timer.current)
    timer.current = setTimeout(commit, COMMIT_MS)
  }, [commit])

  useEffect(
    () => () => {
      cancelAnimationFrame(frame.current)
      // Leaving with a wheel turn not yet written: write it now.
      if (timer.current !== undefined) commit()
    },
    [commit],
  )

  // The view changed from elsewhere (another tab's edit, undoing nothing: a reset): look there.
  useEffect(() => {
    if (sameCamera(camera, committed.current)) return
    committed.current = camera
    cam.current = { ...camera }
    schedule()
  }, [camera, schedule])

  useLayoutEffect(() => {
    const element = box.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry!.contentRect
      setSize({ width: Math.round(width), height: Math.round(height) })
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  /** Zooms by [factor] keeping the block under client point ([cx], [cy]) where it is. */
  const zoomAt = useCallback(
    (cx: number, cy: number, factor: number) => {
      const element = box.current
      if (!element) return
      const r = element.getBoundingClientRect()
      const c = cam.current
      const mx = cx - r.left - r.width / 2
      const my = cy - r.top - r.height / 2
      const zoom = clampZoom(c.zoom * factor)
      const wx = c.x + mx / c.zoom
      const wz = c.z + my / c.zoom
      cam.current = { x: wx - mx / zoom, z: wz - my / zoom, zoom }
      schedule()
      commitSoon()
    },
    [schedule, commitSoon],
  )

  // The wheel and a pinch zoom; both need listeners that may prevent the page's own scroll and zoom.
  useEffect(() => {
    const element = box.current
    if (!element) return
    let pinching = false
    let pinchFrom = 1
    const wheel = (event: WheelEvent) => {
      event.preventDefault()
      // WebKit pinches with gesture events; a ctrl+wheel then is the same pinch again.
      if (pinching && event.ctrlKey) return
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 400 : 1
      const speed = event.ctrlKey ? 0.01 : 0.0025
      zoomAt(event.clientX, event.clientY, Math.exp(-event.deltaY * unit * speed))
    }
    type Gesture = Event & { scale: number; clientX: number; clientY: number }
    const start = (event: Event) => {
      event.preventDefault()
      pinching = true
      pinchFrom = cam.current.zoom
    }
    const change = (event: Event) => {
      event.preventDefault()
      const gesture = event as Gesture
      zoomAt(gesture.clientX, gesture.clientY, (pinchFrom * gesture.scale) / cam.current.zoom)
    }
    const end = (event: Event) => {
      event.preventDefault()
      pinching = false
    }
    element.addEventListener('wheel', wheel, { passive: false })
    element.addEventListener('gesturestart', start)
    element.addEventListener('gesturechange', change)
    element.addEventListener('gestureend', end)
    return () => {
      element.removeEventListener('wheel', wheel)
      element.removeEventListener('gesturestart', start)
      element.removeEventListener('gesturechange', change)
      element.removeEventListener('gestureend', end)
    }
  }, [zoomAt])

  const viewport: Viewport = { ...view, width: size.width, height: size.height }
  const step = stepFor(view.zoom, detail)

  // What's wanted, every time the view moves: the scheduler keeps the workers on it.
  useEffect(() => {
    if (size.width === 0 || size.height === 0) return
    const positions = wantedTiles(
      { ...view, width: size.width, height: size.height },
      detail,
      hover,
    )
    const key = positions.map(tileKey).join(',')
    if (key === wanted.current) return
    wanted.current = key
    scheduler.want(positions)
  }, [scheduler, view, size, detail, hover])

  // The picture: every level from coarse to one finer than shown, so whatever's drawn already fills the gaps.
  useLayoutEffect(() => {
    const element = canvas.current
    const context = element?.getContext('2d')
    if (!element || !context || size.width === 0 || size.height === 0) return
    const dpr = window.devicePixelRatio || 1
    const width = Math.round(size.width * dpr)
    const height = Math.round(size.height * dpr)
    if (element.width !== width) element.width = width
    if (element.height !== height) element.height = height
    const vp = { ...view, width: size.width, height: size.height }
    const { left, top } = viewBounds(vp)
    const scale = view.zoom * dpr
    const sx = (x: number) => Math.round((x - left) * scale)
    const sy = (z: number) => Math.round((z - top) * scale)
    context.setTransform(1, 0, 0, 1, 0, 0)
    context.fillStyle = BACKGROUND
    context.fillRect(0, 0, width, height)
    context.imageSmoothingEnabled = false
    for (const level of [16, 8, 4, 2, 1, 0.5]) {
      const s = step * level
      if (s < MIN_STEP || s > MAX_STEP) continue
      const span = TILE * s
      for (const pos of tilesIn(vp, s)) {
        const tile = scheduler.get(pos)
        if (!tile) continue
        const x0 = sx(pos.tx * span)
        const z0 = sy(pos.tz * span)
        context.drawImage(
          imageOf(tile),
          x0,
          z0,
          sx((pos.tx + 1) * span) - x0,
          sy((pos.tz + 1) * span) - z0,
        )
      }
    }

    // Chunks, once they're big enough to see.
    if (view.zoom >= 3) {
      context.strokeStyle = 'rgb(255 255 255 / 10%)'
      context.lineWidth = 1
      context.beginPath()
      const { across, down } = viewBounds(vp)
      for (let x = Math.ceil(left / 16) * 16; x < left + across; x += 16) {
        context.moveTo(sx(x) + 0.5, 0)
        context.lineTo(sx(x) + 0.5, height)
      }
      for (let z = Math.ceil(top / 16) * 16; z < top + down; z += 16) {
        context.moveTo(0, sy(z) + 0.5)
        context.lineTo(width, sy(z) + 0.5)
      }
      context.stroke()
    }

    // The slice's line, as long as the slice is.
    const from = slice.along - Math.floor(slice.width / 2)
    const a = slice.alongX ? [sx(from), sy(slice.at + 0.5)] : [sx(slice.at + 0.5), sy(from)]
    const b = slice.alongX
      ? [sx(from + slice.width), sy(slice.at + 0.5)]
      : [sx(slice.at + 0.5), sy(from + slice.width)]
    context.lineWidth = 2 * dpr
    context.setLineDash([])
    context.strokeStyle = 'rgb(0 0 0 / 60%)'
    context.beginPath()
    context.moveTo(a[0]!, a[1]!)
    context.lineTo(b[0]!, b[1]!)
    context.stroke()
    context.lineWidth = 1 * dpr
    context.setLineDash([6 * dpr, 4 * dpr])
    context.strokeStyle = '#fff'
    context.stroke()
    context.setLineDash([])

    // The cell under the pointer.
    if (hover) {
      const x = Math.floor(hover.x / step) * step
      const z = Math.floor(hover.z / step) * step
      const x0 = sx(x)
      const z0 = sy(z)
      const w = Math.max(sx(x + step) - x0, 3 * dpr)
      context.strokeStyle = '#fff'
      context.lineWidth = 1 * dpr
      context.strokeRect(x0 + 0.5, z0 + 0.5, w, Math.max(sy(z + step) - z0, 3 * dpr))
    }
  }, [scheduler, version, view, size, step, slice, hover])

  /** The block under a client point, as the map is now. */
  const blockAt = (clientX: number, clientY: number) => {
    const r = box.current!.getBoundingClientRect()
    const c = cam.current
    return {
      x: Math.floor(c.x + (clientX - r.left - r.width / 2) / c.zoom),
      z: Math.floor(c.z + (clientY - r.top - r.height / 2) / c.zoom),
    }
  }

  const pan = (dx: number, dz: number) => {
    const c = cam.current
    cam.current = { ...c, x: c.x + dx, z: c.z + dz }
    schedule()
    commitSoon()
  }

  const onKeyDown = (event: ReactKeyboardEvent) => {
    if (event.metaKey || event.ctrlKey || event.altKey) return
    const c = cam.current
    const by = ((event.shiftKey ? 0.5 : 0.15) * Math.min(size.width, size.height)) / c.zoom
    const r = box.current!.getBoundingClientRect()
    const middle = [r.left + r.width / 2, r.top + r.height / 2] as const
    const actions: Record<string, () => void> = {
      ArrowLeft: () => pan(-by, 0),
      ArrowRight: () => pan(by, 0),
      ArrowUp: () => pan(0, -by),
      ArrowDown: () => pan(0, by),
      '+': () => zoomAt(...middle, 1.5),
      '=': () => zoomAt(...middle, 1.5),
      '-': () => zoomAt(...middle, 1 / 1.5),
      Home: () => {
        cam.current = { x: 0, z: 0, zoom: c.zoom }
        schedule()
        commit()
      },
    }
    const action = actions[event.key]
    if (!action) return
    event.preventDefault()
    action()
  }

  // What's under the pointer, at the level shown (a coarser cell would be another column's ground).
  const cell = hover ? scheduler.cellAt(hover.x, hover.z, [step]) : null
  const meta = scheduler.meta
  let readout = meta ? `Sea level ${meta.seaLevel}.` : ''
  if (hover && cell && meta) {
    const { tile, index } = cell
    const height = tile.heights[index]!
    const area = tile.areas[index]!
    const decorations = new Set<number>()
    for (let k = 0; k + 1 < tile.decorations.length; k += 2) {
      if (tile.decorations[k] === index) decorations.add(tile.decorations[k + 1]!)
    }
    readout =
      `${place(hover.x, hover.z)}: ground at ${height}${height < meta.seaLevel ? ' (under the sea)' : ''}, ` +
      `${meta.areaNames[area] ?? ''} (${meta.biomes[area] ?? ''})` +
      (decorations.size
        ? `; ${[...decorations].map((i) => meta.decorationNames[i]).join(', ')}`
        : '')
  } else if (hover) {
    readout = `${place(hover.x, hover.z)}: drawing…`
  }

  // The decorations marked in view, and whether the view is close enough for them to be.
  const marked = new Set<number>()
  let shown = true
  let pending = false
  if (size.width > 0) {
    for (const pos of tilesIn(viewport, step)) {
      const tile = scheduler.get(pos)
      if (!tile || !scheduler.current(tile)) {
        pending = true
        continue
      }
      if (!tile.decorationsShown) shown = false
      for (let k = 1; k < tile.decorations.length; k += 2) marked.add(tile.decorations[k]!)
    }
  }
  const bar = scaleBar(view.zoom)

  return (
    <div className={styles.mapPanel}>
      <div
        ref={box}
        className={styles.map}
        tabIndex={0}
        role="application"
        aria-roledescription="map"
        aria-label="Map of the terrain from above"
        data-testid="terrain-map"
        data-drawing={pending || undefined}
        onPointerDown={(event) => {
          if (event.button !== 0) return
          event.currentTarget.setPointerCapture(event.pointerId)
          drag.current = { px: event.clientX, py: event.clientY, from: cam.current, moved: false }
        }}
        onPointerMove={(event) => {
          const here = blockAt(event.clientX, event.clientY)
          if (!hover || hover.x !== here.x || hover.z !== here.z) setHover(here)
          const d = drag.current
          if (!d) return
          const dx = event.clientX - d.px
          const dy = event.clientY - d.py
          if (Math.abs(dx) + Math.abs(dy) > 3) d.moved = true
          if (!d.moved) return
          cam.current = {
            ...d.from,
            x: d.from.x - dx / d.from.zoom,
            z: d.from.z - dy / d.from.zoom,
          }
          schedule()
        }}
        onPointerUp={(event) => {
          const d = drag.current
          drag.current = null
          if (!d) return
          if (d.moved) {
            commit()
            return
          }
          const here = blockAt(event.clientX, event.clientY)
          onCut(slice.alongX, slice.alongX ? here.z : here.x, slice.alongX ? here.x : here.z)
        }}
        onPointerCancel={() => {
          drag.current = null
          commit()
        }}
        onPointerLeave={() => setHover(null)}
        onDoubleClick={(event) => zoomAt(event.clientX, event.clientY, 2)}
        onKeyDown={onKeyDown}
      >
        <canvas ref={canvas} className={styles.canvas} />
        <div
          className={styles.mapTools}
          onPointerDown={(event) => event.stopPropagation()}
          onPointerUp={(event) => event.stopPropagation()}
          onDoubleClick={(event) => event.stopPropagation()}
        >
          <button
            type="button"
            aria-label="Zoom in"
            title="Zoom in (+)"
            onClick={() => zoomAt(...middleOf(box.current), 2)}
          >
            +
          </button>
          <button
            type="button"
            aria-label="Zoom out"
            title="Zoom out (-)"
            onClick={() => zoomAt(...middleOf(box.current), 0.5)}
          >
            −
          </button>
          <button
            type="button"
            aria-label="Go to 0, 0"
            title="Go to x 0, z 0 (Home)"
            onClick={() => {
              cam.current = { ...cam.current, x: 0, z: 0 }
              schedule()
              commit()
            }}
          >
            ⌂
          </button>
        </div>
        <div className={styles.scale} aria-hidden="true">
          <span style={{ width: bar.pixels }} />
          {bar.blocks} {bar.blocks === 1 ? 'block' : 'blocks'} ·{' '}
          {step === 1 ? 'every block' : `a cell every ${step}`}
          {pending && ' · drawing…'}
        </div>
        <p className={styles.readout} aria-live="polite" data-testid="terrain-map-readout">
          {readout}
        </p>
        {meta && (
          <div
            className={styles.legends}
            onPointerDown={(event) => event.stopPropagation()}
            onPointerUp={(event) => event.stopPropagation()}
            onDoubleClick={(event) => event.stopPropagation()}
          >
            <ul className={styles.legend} aria-label="Biome areas">
              {meta.areaNames.map((name, i) => {
                const [r, g, b] = areaColor(i)
                return (
                  <li key={name}>
                    <span className={styles.swatch} style={{ background: `rgb(${r} ${g} ${b})` }} />
                    {name} <Muted>{meta.biomes[i]}</Muted>
                  </li>
                )
              })}
              <li>
                <span className={styles.swatch} style={{ background: 'rgb(48 96 184)' }} /> the sea
              </li>
            </ul>
            {meta.decorationNames.length > 0 && (
              <ul
                className={styles.legend}
                aria-label="Decorations"
                data-testid="terrain-decorations"
              >
                {meta.decorationNames.map((name, i) => {
                  if (!marked.has(i)) return null
                  const [r, g, b] = decorationColor(i)
                  return (
                    <li key={name}>
                      <span
                        className={styles.swatch}
                        style={{ background: `rgb(${r} ${g} ${b})` }}
                      />
                      {name}
                    </li>
                  )
                })}
                {!shown && (
                  <li>
                    <Muted>Zoom in to see where decorations start.</Muted>
                  </li>
                )}
              </ul>
            )}
          </div>
        )}
      </div>
    </div>
  )
})

/** The middle of [element] in client coordinates. */
function middleOf(element: HTMLElement | null): [number, number] {
  const r = element?.getBoundingClientRect()
  return r ? [r.left + r.width / 2, r.top + r.height / 2] : [0, 0]
}
