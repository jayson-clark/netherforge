/**
 * The recipe editor's per-document view: where a shaped recipe's pattern
 * sits on the grid, so a shape stays where the user put it after its
 * pattern is trimmed. Nothing is selected.
 */
import type { ViewStateSpec } from '@/editors/contributions'
import { ORIGIN, type GridOffset } from './ops'

export interface RecipeView {
  offset: GridOffset
}

export const RECIPE_VIEW: ViewStateSpec<RecipeView, never> = {
  initial: { offset: ORIGIN },
  key: (item) => item,
}
