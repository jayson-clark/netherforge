import { describe, expect, it } from 'vitest'
import { layoutText } from '@/core/format'
import { fixtureGlyphAdvances } from '@/testing/clientFixture'
import { plainText, runsIn, styledChars } from './minimessage'

const GOLD = '#ffaa00'
const RED = '#ff5555'

describe('minimessage', () => {
  it("styles characters from format's pass, over a base style", () => {
    const chars = styledChars('<red>a<bold>b</bold></red>c')
    expect(chars.map((it) => [it.char, it.at])).toEqual([
      ['a', 5],
      ['b', 12],
      ['c', 26],
    ])
    expect(chars[1]!.style).toEqual({ color: RED, bold: true })
    expect(chars[2]!.style).toEqual({})
    // The base is under every tag; <reset> and turning a decoration off return to it or past it.
    const lore = styledChars('<red>a<reset>b<!italic>c', { color: GOLD, italic: true })
    expect(lore.map((it) => it.style)).toEqual([
      { color: RED, italic: true },
      { color: GOLD, italic: true },
      { color: GOLD, italic: false },
    ])
    expect(plainText('a<hover:show_text:hi>b</hover>c\n<newline>d')).toBe('abcd')
  })

  it('reads glyph tags as one picture each, where format measures them', () => {
    const text = '<gray><glyph:ui/coin> 2</gray><bold>x</bold>'
    const chars = styledChars(text)
    expect(chars[0]).toMatchObject({ char: '', glyph: 'ui/coin', at: 6 })
    // It opens nothing: the </gray> after it still closes the grey.
    expect(chars[3]!.style).toEqual({ bold: true })
    expect(runsIn(chars).map((run) => run.glyph ?? run.text)).toEqual(['ui/coin', ' 2', 'x'])
    expect(plainText(text)).toBe(' 2x')
    const [line] = layoutText(text, 0, fixtureGlyphAdvances, { 'ui/coin': 9 })
    expect(line!.start).toBe(0)
    expect(line!.width).toBe(9 + layoutText(' 2<bold>x', 0, fixtureGlyphAdvances)[0]!.width)
  })

  it("cuts format's wrapped lines out of the styled characters", () => {
    const text = '<gold>Welcome to the server, friend</gold>'
    const lines = layoutText(text, 60, fixtureGlyphAdvances)
    expect(lines.length).toBeGreaterThan(1)
    const chars = styledChars(text)
    const shown = lines.map((line) =>
      runsIn(chars, line.start, line.end)
        .map((run) => run.text)
        .join(''),
    )
    expect(shown.join(' ')).toBe('Welcome to the server, friend')
    for (const line of lines) {
      for (const run of runsIn(chars, line.start, line.end)) expect(run.style.color).toBe(GOLD)
    }
  })

  it('reads a space as a move and nests what an at holds in its overlay', () => {
    const chars = styledChars('<at:-30:align=right><glyph:ui/head> !</at>King<space:-3>')
    const runs = runsIn(chars)
    expect(runs[0]!.overlay!.at).toEqual({ x: -30, from: 'cursor', align: 'right' })
    expect(runs[0]!.overlay!.runs.map((run) => run.glyph ?? run.text)).toEqual(['ui/head', ' !'])
    expect(runs.slice(1).map((run) => run.move ?? run.text)).toEqual(['King', -3])
    expect(plainText('<at:4>a</at>b<space:2>c')).toBe('abc')
    // What the at holds takes no width; the move is its pixels.
    const [line] = layoutText('<at:4>abc</at>b<space:2>', 0, fixtureGlyphAdvances)
    expect(line!.width).toBe(layoutText('b', 0, fixtureGlyphAdvances)[0]!.width + 2)
  })
})
