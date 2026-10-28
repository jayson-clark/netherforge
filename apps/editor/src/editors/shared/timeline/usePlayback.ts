/**
 * A timeline's playback: while [playing], on every animation frame,
 * [onFrame] gets where the playhead was when playing began ([from], read
 * then) and the seconds since, and moves the playhead by the editor's own
 * rules (a centity's clip loops, holds or stops; a particle preview counts
 * whole ticks). Playing starts over, from the playhead as it is, when
 * [playing] or anything in [restart] changes.
 */
import { useEffect, useEffectEvent } from 'react'

export function usePlayback({
  playing,
  from,
  onFrame,
  restart = [],
}: {
  playing: boolean
  from: number
  onFrame: (from: number, elapsed: number) => void
  restart?: readonly unknown[]
}) {
  const start = useEffectEvent(() => from)
  const frame = useEffectEvent(onFrame)
  useEffect(() => {
    if (!playing) return
    const began = performance.now()
    const at = start()
    let request = 0
    const tick = () => {
      frame(at, (performance.now() - began) / 1000)
      request = requestAnimationFrame(tick)
    }
    request = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(request)
    // [restart] is the caller's list of what starts playing over.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playing, ...restart])
}
