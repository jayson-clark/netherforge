/**
 * `fonts/default.json` (docs/format/project.md): the default font's advances
 * for the target version, which the server measures text with. Only the
 * client knows them, so the editor writes them from the import, the way it
 * fits model hitboxes; it's committed and deployed with the project. Pure: the
 * workspace decides when to ask.
 *
 * Without an import there's nothing to write from, and that alone isn't a
 * problem: format warns where something needs the file (`font.needed`) or
 * where it's stale (`font.stale`), so a project that measures no text opens
 * clean either way.
 */
import { canonicalizeModel, DEFAULT_FONT_FILE, type Problem } from '@/core/format'

export { DEFAULT_FONT_FILE }

/** The file's canonical text for [minecraft], from the import's advances (`{ "<code point>": px }`). */
export function defaultFontText(
  minecraft: string,
  advances: Record<string, number>,
): string | null {
  return canonicalizeModel('default_font', DEFAULT_FONT_FILE, { minecraft, advances }).text ?? null
}

/**
 * The text to write when the import's differs from what's on disk
 * ([diskText], undefined when there's no file), or null: nothing to do, or
 * nothing to write from (no import for [minecraft]).
 */
export function defaultFontToWrite(
  minecraft: string | null,
  advances: Record<string, number> | null,
  diskText: string | undefined,
): string | null {
  if (!minecraft || !advances || Object.keys(advances).length === 0) return null
  const text = defaultFontText(minecraft, advances)
  return text !== null && text !== diskText ? text : null
}

/** The problem shown while writing the file fails, until a write succeeds. */
export function defaultFontWriteFailed(error: string): Problem {
  return {
    severity: 'warning',
    file: DEFAULT_FONT_FILE,
    message: `Couldn't write the default font's advances, which the server needs to measure text: ${error}`,
    code: 'editor.default-font',
  }
}
