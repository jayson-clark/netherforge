/**
 * A menu's picture: its window in miniature, as the menu editor draws it (the skin, the title, the slots'
 * items), as it is now (unsaved edits too).
 */
import type { MenuFile } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { windowLayout } from '@/minecraft/window/window'
import { useSkin, WindowFrame } from '@/minecraft/window/WindowFrame'
import { useWorkspace } from '@/state/providers'
import styles from './MenuThumbnail.module.css'

export function MenuThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'menu', id)))
  const model = parseCached<MenuFile>(text)
  const skin = useSkin(typeof model?.skin === 'string' ? model.skin : undefined)
  if (typeof model !== 'object' || model === null) return <>{fallback}</>
  const layout = windowLayout(model.type, model.rows)
  const scale = size / Math.max(layout.width, layout.height)
  return (
    <span className={styles.window}>
      <WindowFrame
        type={model.type}
        rows={model.rows}
        title={model.title}
        skin={skin}
        scale={scale}
        renderSlot={({ index, x, y }) => {
          const item = model.slots?.[String(index)]?.item
          return (
            item && (
              <span key={index} className={styles.slot} style={{ left: x * scale, top: y * scale }}>
                {/* A count is too small to read here. */}
                <ItemIcon item={{ ...item, count: undefined }} size={16 * scale} />
              </span>
            )
          )
        }}
      />
    </span>
  )
}
