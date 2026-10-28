/**
 * An item stack as a slot shows it: its picture (a pack's item model, the
 * client's sprite, or a 3D model rendered the way the GUI draws it: the
 * client's, or a pack block's cube), the
 * enchantment shimmer, and the count. Without client assets it's a labelled
 * placeholder, never an error.
 */
import { useEffect, useMemo, useState } from 'react'
import type { ItemDef } from '@/core/format'
import { blockLookPicture, itemPicture, packModelPicture } from '@/minecraft/client/iconRender'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import {
  useCompiledResourcePacks,
  useHomeNamespace,
  useProjectFileUrl,
  useResourcePackFiles,
  useResourcePackModels,
  useReferenceNamespace,
} from '@/state/useResourcePacks'
import { cx } from '@/ui/cx'
import { usePendingPicture } from '@/ui/Reveal'
import { resourcePackItemLook, vanillaIconSource, type IconSource } from './icon'
import styles from './ItemIcon.module.css'
import { hasGlint, itemLabel, stackLook } from './item'
import { useProjectItem } from './projectItems'

type Picture = { layers: string[] } | { label: string }

interface Worked {
  source: IconSource
  /** A drawn picture (a model, a block, a shape), null when it couldn't be. */
  rendered?: string | null
  /** The project files' URLs it was drawn from (`filesOf`): it's drawn again when they change. */
  files: string
}

/**
 * Every look worked out this session, by `useLookPicture`'s key: an icon shown again
 * (another page, a tab come back) shows at once rather than after its effect.
 */
const worked = new Map<string, Worked>()

/** The project files [source] is drawn from, by face or texture name, as URLs (which change with the file). */
function urlsOf(source: IconSource, projectUrl: (path: string) => string): Record<string, string> {
  const paths =
    source.kind === 'block' ? source.faces : source.kind === 'shape' ? source.textures : {}
  return Object.fromEntries(Object.entries(paths).map(([name, path]) => [name, projectUrl(path)]))
}

const filesOf = (source: IconSource, projectUrl: (path: string) => string) =>
  JSON.stringify(urlsOf(source, projectUrl))

/**
 * The picture layers of [look] (a pack's look, worked out by the caller), else of the
 * client's own model for item [kind]; null while it draws or when there's nothing to draw.
 * Only the look keys the picture: the packs are large, and a gallery has hundreds of
 * icons, so a key made from them costs more than the drawing. While it's worked out it
 * holds back the page it's on (`usePendingPicture`).
 */
export function useLookPicture(look: IconSource | null, kind: string | undefined): string[] | null {
  const assets = useClientAssets()
  const projectUrl = useProjectFileUrl()
  const [, setVersion] = useState(0)
  const lookKey = useMemo(() => JSON.stringify(look), [look])
  const key = `${kind}|${assets?.key ?? ''}|${lookKey}`
  const known = worked.get(key)
  // A picture drawn from project files that have changed since is drawn again, shown as it was meanwhile.
  const current = known !== undefined && known.files === filesOf(known.source, projectUrl)
  usePendingPicture(known === undefined)

  useEffect(() => {
    if (current) return
    let live = true
    void (look ? Promise.resolve(look) : vanillaIconSource(kind, assets)).then(
      async (source: IconSource) => {
        const files = filesOf(source, projectUrl)
        const urls = urlsOf(source, projectUrl)
        const rendered = !assets
          ? null
          : source.kind === 'model'
            ? await itemPicture(assets, source.item)
            : source.kind === 'block'
              ? await blockLookPicture(assets, urls)
              : source.kind === 'shape'
                ? await packModelPicture(assets, source.model, urls)
                : null
        worked.set(key, { source, rendered, files })
        if (live) setVersion((it) => it + 1)
      },
    )
    return () => {
      live = false
    }
    // `key` covers the kind, the look and the assets; `current` the project files a drawing used.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, current])

  if (!known) return null
  const { source } = known
  switch (source.kind) {
    case 'project':
      return [projectUrl(source.path)]
    case 'sprite':
      return assets ? source.textures.map((it) => assets.textureUrl(it)) : null
    case 'model':
    case 'block':
    case 'shape':
      return known.rendered ? [known.rendered] : null
    case 'none':
      return null
  }
}

function useItemPicture(item: Pick<ItemDef, 'kind' | 'itemModel'>): Picture {
  const packs = useResourcePackFiles()
  const compiled = useCompiledResourcePacks()
  const namespace = useReferenceNamespace()
  const home = useHomeNamespace()
  const models = useResourcePackModels()
  const look = useMemo(
    () => resourcePackItemLook(item.itemModel, packs, compiled, namespace, home, models),
    [item.itemModel, packs, compiled, namespace, home, models],
  )
  const layers = useLookPicture(look, item.kind)
  return layers ? { layers } : { label: itemLabel(item.kind || '?') }
}

export function ItemIcon({ item: stack, size = 32 }: { item: ItemDef; size?: number }) {
  // A project item's stack looks like its definition, with the stack's own fields on top.
  const item = stackLook(stack, useProjectItem(stack.item))
  const picture = useItemPicture(item)
  const count = item.count ?? 1
  return (
    <span
      className={cx(styles.icon, hasGlint(item) && styles.glint)}
      style={{ width: size, height: size }}
    >
      {'layers' in picture ? (
        <Layers layers={picture.layers} />
      ) : (
        <span className={styles.placeholder} style={{ fontSize: Math.max(8, size / 4) }}>
          {picture.label
            .split(' ')
            .map((word) => word[0])
            .join('')
            .slice(0, 3)
            .toUpperCase()}
        </span>
      )}
      {count > 1 && <span className={styles.count}>{count}</span>}
    </span>
  )
}

function Layers({ layers }: { layers: string[] }) {
  return layers.map((url, index) => (
    <img key={index} src={url} alt="" draggable={false} className={styles.layer} />
  ))
}

/** Picture [layers] (from [useLookPicture]) drawn as an icon's are, [size] pixels square. */
export function LookIcon({ layers, size }: { layers: string[]; size: number }) {
  return (
    <span className={styles.icon} style={{ width: size, height: size }}>
      <Layers layers={layers} />
    </span>
  )
}
