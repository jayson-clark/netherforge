/**
 * What the datapack screen shows of a pack: its formats in words and its files
 * by where they are. Pure, so it's tested without React.
 */
import type { PackMeta } from '@/core/format'

/** A `min_format` or `max_format` as the game reads it: `94`, or `[94, 1]` as `94.1`; null when it's neither. */
export function formatText(value: unknown): string | null {
  if (typeof value === 'number' && Number.isInteger(value)) return `${value}`
  if (Array.isArray(value) && value.length >= 1 && value.length <= 2) {
    if (!value.every((part) => typeof part === 'number' && Number.isInteger(part))) return null
    return value.join('.')
  }
  return null
}

/** `94 to 121`, or what's wrong with it. */
export function rangeText(min: unknown, max: unknown): string {
  const from = formatText(min)
  const to = formatText(max)
  if (from === null || to === null) return 'not data pack formats (see Problems)'
  return from === to ? from : `${from} to ${to}`
}

/** One folder of the pack's files: its own `data/`, or an overlay's, with the formats that overlay is for. */
export interface FileGroup {
  /** `data` for the pack's own, else the overlay's folder. */
  folder: string
  /** The formats it applies to, in words: the pack's for its own. */
  formats: string
  /** Each file's path in the pack's folder, sorted. */
  files: string[]
}

/**
 * The pack's files (paths in its folder, `pack.mcmeta` left out) in its own
 * `data/` and each overlay's folder, in the order the game applies them, then
 * anything else (which Problems explains).
 */
export function fileGroups(meta: PackMeta, files: string[]): FileGroup[] {
  const overlays = meta.overlays?.entries ?? []
  const groups: FileGroup[] = [
    { folder: 'data', formats: rangeText(meta.pack.min_format, meta.pack.max_format), files: [] },
    ...overlays.map((overlay) => ({
      folder: overlay.directory,
      formats: rangeText(overlay.min_format, overlay.max_format),
      files: [] as string[],
    })),
  ]
  const other: FileGroup = { folder: '', formats: '', files: [] }
  for (const file of [...files].sort()) {
    if (file === 'pack.mcmeta') continue
    const top = file.split('/')[0]
    const group =
      top === 'data'
        ? groups[0]
        : groups.find(
            (it, index) => index > 0 && it.folder === top && file.startsWith(`${top}/data/`),
          )
    ;(group ?? other).files.push(file)
  }
  return other.files.length > 0 ? [...groups, other] : groups
}

/** A format as typed back into the file: `94` is 94, `94.1` is `[94, 1]`; null when it's neither. */
export function parseFormat(text: string): number | [number, number] | null {
  const match = /^\s*(\d+)(?:\.(\d+))?\s*$/.exec(text)
  if (!match) return null
  const major = Number(match[1])
  return match[2] === undefined ? major : [major, Number(match[2])]
}

/** Adds an overlay for [directory], for the pack's own formats to start from. */
export function addOverlay(meta: PackMeta, directory: string): void {
  meta.overlays ??= {}
  meta.overlays.entries = [
    ...(meta.overlays.entries ?? []),
    { min_format: meta.pack.min_format, max_format: meta.pack.max_format, directory },
  ]
}

/** Removes overlay [index]; none left removes `overlays`. */
export function removeOverlay(meta: PackMeta, index: number): void {
  const entries = meta.overlays?.entries
  if (!entries) return
  entries.splice(index, 1)
  if (entries.length === 0) delete meta.overlays
}
