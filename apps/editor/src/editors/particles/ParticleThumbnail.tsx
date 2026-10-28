/**
 * A particle effect's picture: the first few of its emitters' sprites, as the preview draws them (the
 * particle's sprite, a block's or an item's), in their colour, in a collage; as it is now (unsaved edits too).
 */
import { useMemo } from 'react'
import type { EmitterDef, ParticleEffectFile, SpawnData } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useWorkspace } from '@/state/providers'
import { Collage, COLLAGE_CELLS } from '@/ui/Collage'
import { usePendingPicture } from '@/ui/Reveal'
import { useAsync } from '@/ui/useAsync'
import { spriteTexture } from './sprites'
import styles from './ParticleThumbnail.module.css'

interface Sprite {
  url: string
  /** The colour it's tinted, or null for its own. */
  tint: string | null
}

/** What one emitter's spawns carry that changes their sprite. */
function dataOf(emitter: EmitterDef): SpawnData | null {
  if (emitter.blockState) return { type: 'block', state: emitter.blockState }
  if (emitter.item) return { type: 'item', item: emitter.item }
  return null
}

const tintOf = (emitter: EmitterDef) => emitter.color ?? emitter.curves?.color?.[0]?.color ?? null

export function ParticleThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'particle_effect', id)))
  const emitters = parseCached<ParticleEffectFile>(text)?.emitters
  const assets = useClientAssets()
  // The emitters that look different, as the sprite and tint they're drawn with.
  const wanted = useMemo(() => {
    const out = new Map<string, EmitterDef>()
    for (const name of Object.keys(emitters ?? {}).sort()) {
      const emitter = emitters![name]
      if (typeof emitter?.particle !== 'string') continue
      const key = JSON.stringify([emitter.particle, dataOf(emitter), tintOf(emitter)])
      if (!out.has(key)) out.set(key, emitter)
    }
    return [...out.values()]
  }, [emitters])
  const key = `${assets?.key ?? ''}|${JSON.stringify(wanted)}`
  const sprites = useAsync(key, () =>
    assets
      ? Promise.all(
          wanted.map(async (emitter) => {
            const texture = await spriteTexture(assets, emitter.particle, dataOf(emitter)).catch(
              () => null,
            )
            return texture ? { url: assets.textureUrl(texture), tint: tintOf(emitter) } : null
          }),
        ).then((all) => all.filter((it): it is Sprite => it !== null).slice(0, COLLAGE_CELLS))
      : null,
  )
  usePendingPicture(assets !== null && wanted.length > 0 && sprites === null)
  if (!sprites || sprites.length === 0) return <>{fallback}</>
  return (
    <Collage
      items={sprites}
      keyOf={(it) => `${it.url}|${it.tint}`}
      render={(it, cell) => <SpriteIcon sprite={it} size={Math.max(8, cell - 6)} />}
      size={size}
    />
  )
}

/** A sprite, tinted as the game tints it: its own shading times the colour, in its own shape. */
function SpriteIcon({ sprite, size }: { sprite: Sprite; size: number }) {
  const mask = `url("${sprite.url}") center / contain no-repeat`
  return (
    <span className={styles.sprite} style={{ width: size, height: size }}>
      {sprite.tint && (
        <span className={styles.tint} style={{ background: sprite.tint, mask, WebkitMask: mask }} />
      )}
      <img
        className={sprite.tint ? styles.multiply : undefined}
        src={sprite.url}
        alt=""
        draggable={false}
      />
    </span>
  )
}
