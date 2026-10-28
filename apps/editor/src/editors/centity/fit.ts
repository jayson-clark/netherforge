/**
 * "Fit to display": turns what a node's display looks like into explicit
 * hitbox boxes the server can use without knowing client-only facts (see the
 * game-data skill). The result records what it was fitted to in `fittedTo`,
 * which the validator compares against the display to warn when it's stale.
 */
import {
  fitTextHitbox,
  type Box,
  type DisplayDef,
  type GameDataBundle,
  type HitboxDef,
  TEXT_DEFAULTS,
} from '@/core/format'
import { modelBoxes, type Aabb } from '@/minecraft/client/boxes'
import { blockPlacements, resolveModel, type AssetLoader } from '@netherforge/models/model'

// The canonical block state and what a fitted hitbox records are format's
// (`BlockState`, `CentityValidator.fitKey`): the validator compares them.
import { canonicalBlockState, fitKey } from '@/core/format'
export { canonicalBlockState, fitKey }

/**
 * Whether the node's display is something Fit can measure: a block (its
 * model), or a text display with the `fixed` billboard (any other turns to
 * face each viewer, so it has no one shape; see `TextMetrics.box`).
 */
export function canFit(display: DisplayDef | undefined): boolean {
  if (display?.type === 'block') return true
  return display?.type === 'text' && (display.billboard ?? TEXT_DEFAULTS.billboard) === 'fixed'
}

/**
 * The box a fixed text display fills, measured by format with the default
 * font's [advances] (unknown glyphs are estimated, so it's close even before
 * an import) and pack [glyphs]' advances for `<glyph:…>` tags; null for any
 * other billboard.
 */
export function textDisplayBoxes(
  display: Extract<DisplayDef, { type: 'text' }>,
  advances: Record<string, number> | null,
  glyphs: Record<string, number> | null = null,
): Box[] | null {
  const box = fitTextHitbox(display, advances, glyphs)
  return box ? [box] : null
}

export interface FitSources {
  gameData: GameDataBundle | null
  /** Client assets, when imported for the project's version. */
  assets: AssetLoader | null
}

/**
 * The boxes a block state is drawn from: the cached game data's model boxes
 * when it has them, else computed from the client's blockstate and model
 * files. Null when neither is available.
 */
export async function blockModelBoxes(state: string, sources: FitSources): Promise<Box[] | null> {
  const canonical = canonicalBlockState(state)
  if (!canonical) return null
  const id = canonical.replace(/\[.*$/, '')
  const known = sources.gameData?.models?.[canonical] ?? sources.gameData?.models?.[id]
  if (known && known.length > 0) return known
  if (!sources.assets) return null
  const defaults = sources.gameData?.blocks?.[id]?.defaults ?? {}
  const placements = await blockPlacements(sources.assets, canonical, defaults)
  if (placements.length === 0) return null
  const models = await Promise.all(
    placements.map(async (placement) => ({
      placement,
      elements: (await resolveModel(sources.assets!, placement.model))?.elements ?? [],
    })),
  )
  const boxes = modelBoxes(models)
  return boxes.length > 0 ? boxes.map(toBox) : null
}

const toBox = (box: Aabb): Box => ({ min: box.min, max: box.max })

/**
 * The hitbox after fitting [boxes] to [display]: explicit boxes plus
 * `fittedTo`, keeping `raycast`, dropping `shape` (it's one or the other).
 */
export function fittedHitbox(
  hitbox: HitboxDef | undefined,
  display: DisplayDef,
  boxes: Box[],
): HitboxDef {
  const next: HitboxDef = { boxes, fittedTo: fitKey(display) ?? undefined }
  if (hitbox?.raycast !== undefined) next.raycast = hitbox.raycast
  return next
}
