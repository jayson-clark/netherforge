/**
 * A dialog's picture: its screen in miniature, as the dialog editor draws it (title, body, inputs, buttons),
 * as it is now (unsaved edits too).
 */
import type { DialogFile } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { useWorkspace } from '@/state/providers'
import { useListedDialogs } from './listed'
import { DialogPreview } from './DialogPreview'
import styles from './DialogThumbnail.module.css'

/** The screen drawn, in GUI pixels: wide enough for two columns of buttons. */
const SCREEN = { width: 320, height: 240 }

const NO_DIALOGS: string[] = []
const pickNothing = () => {}

export function DialogThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'dialog', id)))
  const model = parseCached<DialogFile>(text)
  const glyphs = useGlyphMap()
  const listed = useListedDialogs(Array.isArray(model?.dialogs) ? model.dialogs : NO_DIALOGS)
  if (typeof model !== 'object' || model === null || typeof model.title !== 'string')
    return <>{fallback}</>
  const scale = size / SCREEN.width
  return (
    // Its elements are buttons in the editor; here they're only a picture.
    <span
      className={styles.screen}
      style={{ width: SCREEN.width * scale, height: SCREEN.height * scale }}
      inert
    >
      <DialogPreview
        model={model}
        listed={listed}
        glyphs={glyphs}
        picked={null}
        onPick={pickNothing}
        scale={scale}
      />
    </span>
  )
}
