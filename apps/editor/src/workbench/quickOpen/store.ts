/** Whether the palette is open, and what it opens with: the toolbar's box, the keys and the Edit menu open it. */
import { create } from 'zustand'

export const useQuickOpen = create<{ open: boolean; query: string }>(() => ({
  open: false,
  query: '',
}))
export const openQuickOpen = () => useQuickOpen.setState({ open: true, query: '' })
export const openCommandPalette = () => useQuickOpen.setState({ open: true, query: '>' })
