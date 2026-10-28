import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GallerySection } from './Gallery'

afterEach(cleanup)

const cards = ['coin', 'gem', 'shop'].map((key) => ({
  key,
  label: `glyph ${key}`,
  caption: key,
  picture: <span />,
}))

describe('GallerySection', () => {
  it('counts its cards, shows the picked one, and picks with the pointer or the keyboard', async () => {
    const onPick = vi.fn()
    const user = userEvent.setup()
    render(<GallerySection title="Glyphs" cards={cards} picked="gem" onPick={onPick} />)
    expect(screen.getByText('Glyphs (3)')).toBeTruthy()
    const grid = screen.getByRole('grid', { name: 'Glyphs' })
    const card = (name: string) => within(grid).getByRole('row', { name })
    expect(card('glyph gem').getAttribute('aria-selected')).toBe('true')
    expect(card('glyph coin').getAttribute('aria-selected')).toBe('false')

    await user.click(card('glyph shop'))
    expect(onPick).toHaveBeenLastCalledWith('shop')

    // One tab stop; the arrows go from card to card, Space picks.
    card('glyph shop').focus()
    await user.keyboard('{ArrowLeft}{ArrowLeft}')
    expect(document.activeElement).toBe(card('glyph coin'))
    await user.keyboard(' ')
    expect(onPick).toHaveBeenLastCalledWith('coin')
  })
})
