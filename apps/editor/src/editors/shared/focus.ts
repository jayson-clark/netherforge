/**
 * Focus requests (from Problems) inside a visual editor: a JSON path from
 * format (`$.slots["13"].item.id`) becomes the `data-path` of the closest
 * field, which is focused and flashed.
 */
import type { JsonPathSegment } from '@/core/paths'
import { FLASH_CLASS } from '@/ui/fields'

const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/

/**
 * Segments as a field's `data-path`: `slots["13"].item.id`, `buttons[0].label`.
 * Plain names join with dots, other keys are quoted, indices bracketed, the
 * way format writes JSON paths (without the `$`).
 */
export function fieldPath(segments: JsonPathSegment[]): string {
  return segments.reduce<string>((out, segment) => {
    if (typeof segment === 'number') return `${out}[${segment}]`
    if (!IDENT.test(segment)) return `${out}[${JSON.stringify(segment)}]`
    return out ? `${out}.${segment}` : segment
  }, '')
}

/**
 * Focuses the field closest to [target], walking up the path; each step looks
 * through [containers] in order.
 */
export function focusField(containers: (Element | null)[], target: string) {
  const within = containers.filter((it): it is Element => it !== null)
  if (within.length === 0) return
  const find = (selector: string) => {
    for (const container of within) {
      const found = container.querySelector<HTMLElement>(selector)
      if (found) return found
    }
    return null
  }
  let candidate = target
  for (;;) {
    const element = candidate
      ? find(`[data-path="${CSS.escape(candidate)}"]`)
      : find('input, select, textarea')
    if (element) {
      const focusable = element.matches('input, select, textarea, button')
        ? element
        : element.querySelector<HTMLElement>('input, select, textarea, button')
      element.scrollIntoView({ block: 'center' })
      ;(focusable ?? element).focus()
      const flash = FLASH_CLASS
      if (flash) {
        element.classList.add(flash)
        setTimeout(() => element.classList.remove(flash), 1200)
      }
      return
    }
    if (!candidate) return
    const cut = Math.max(candidate.lastIndexOf('.'), candidate.lastIndexOf('['))
    candidate = cut > 0 ? candidate.slice(0, cut) : ''
  }
}
