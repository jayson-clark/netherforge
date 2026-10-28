import { describe, expect, it } from 'vitest'
import { loadProject } from '@/core/format'
import { exampleProject, examplePackages } from '@/testing/fixtures'
import { DEFAULT_FONT_FILE, defaultFontText, defaultFontToWrite } from './defaultFont'

/** The example project as validation reads it: JSON as text, everything else null. */
const project = (): Record<string, string | null> =>
  Object.fromEntries(
    Object.entries(exampleProject).map(([path, it]) => [path, typeof it === 'string' ? it : null]),
  )

describe('the default font file', () => {
  const advances = { '97': 6, '32': 4 }

  it("is the import's advances for the target version, canonical and valid", () => {
    const text = defaultFontText('26.3', advances)!
    expect(text).toContain('"minecraft": "26.3"')
    expect(text.indexOf('"32"')).toBeLessThan(text.indexOf('"97"'))
    const files = { ...project(), [DEFAULT_FONT_FILE]: text }
    expect(loadProject(files, null, examplePackages).problems).toEqual([])
  })

  it('is written only when the import has something different to say', () => {
    const text = defaultFontText('26.3', advances)!
    expect(defaultFontToWrite('26.3', advances, undefined)).toBe(text)
    expect(defaultFontToWrite('26.3', advances, text)).toBeNull()
    // Stale: written for another version.
    expect(defaultFontToWrite('26.3', advances, defaultFontText('26.2', advances)!)).toBe(text)
    // Nothing to write from.
    expect(defaultFontToWrite('26.3', null, undefined)).toBeNull()
    expect(defaultFontToWrite('26.3', {}, undefined)).toBeNull()
    expect(defaultFontToWrite(null, advances, undefined)).toBeNull()
  })

  it('is stale to format when the project targets another version', () => {
    const files = { ...project(), [DEFAULT_FONT_FILE]: defaultFontText('26.2', advances) }
    expect(loadProject(files, null, examplePackages).problems.map((it) => it.code)).toEqual([
      'font.stale',
    ])
  })
})
