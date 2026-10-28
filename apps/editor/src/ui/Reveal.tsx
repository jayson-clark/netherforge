/**
 * Holds a page of pictures back until they're ready, so it shows whole rather
 * than as placeholders turning into pictures one by one. A picture still being
 * worked out says so with `usePendingPicture(true)`; the page (`useReveal`)
 * shows once nothing under its `RevealGate` is pending and every `<img>` in it
 * has decoded, or after `MAX_WAIT_MS` whatever is left. Once shown it stays
 * shown until its `page` key changes: a picture that changes later swaps in place.
 */
import { createContext, useContext, useEffect, useRef, useState, type RefObject } from 'react'

/** The longest a page waits for its pictures. */
export const MAX_WAIT_MS = 1200

export interface Gate {
  hold: () => () => void
}

const GateContext = createContext<Gate | null>(null)

/** Gives the pictures under it the gate [useReveal] made. */
export const RevealGate = GateContext.Provider

/** Marks a picture as still being worked out while [pending], holding back the nearest gate's page. */
export function usePendingPicture(pending: boolean) {
  const gate = useContext(GateContext)
  useEffect(() => (pending && gate ? gate.hold() : undefined), [pending, gate])
}

/**
 * Whether page [page] (what's on it: another key hides it again) of the element at [box] may show yet, and the
 * gate its pictures hold.
 */
export function useReveal(
  box: RefObject<HTMLElement | null>,
  page: string,
): { gate: Gate; shown: boolean } {
  const held = useRef(0)
  const [shownFor, setShownFor] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  const [gate] = useState<Gate>(() => ({
    hold: () => {
      held.current += 1
      let released = false
      return () => {
        if (released) return
        released = true
        held.current -= 1
        setTick((it) => it + 1)
      }
    },
  }))
  const shown = shownFor === page

  useEffect(() => {
    const timer = setTimeout(() => setShownFor(page), MAX_WAIT_MS)
    return () => clearTimeout(timer)
  }, [page])

  // The pictures' effects (their holds) run before this one, so the count is theirs.
  useEffect(() => {
    if (shown || held.current > 0) return
    let live = true
    const images = [...(box.current?.querySelectorAll('img') ?? [])]
    void Promise.all(
      images.map((img) =>
        typeof img.decode === 'function' ? img.decode().catch(() => undefined) : undefined,
      ),
    ).then(() => {
      if (live && held.current === 0) setShownFor(page)
    })
    return () => {
      live = false
    }
  }, [box, page, tick, shown])

  return { gate, shown }
}
