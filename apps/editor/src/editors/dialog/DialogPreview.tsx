/**
 * A dialog screen laid out like the game's: the title at the top, then the
 * body and inputs in a centred column, then the buttons (in columns for a
 * multi_action or a dialog_list, along the bottom otherwise). Message text is
 * wrapped by format's `layoutText` at the message's width, with the imported
 * client's glyph advances, so lines break where the game breaks them; the
 * widgets around it are drawn at the game's sizes, in the editor's style.
 *
 * Every element is a button that picks it in the inspector. An item body
 * shows its tooltip on hover, as the game does unless it says not to.
 */
import { useMemo, type CSSProperties } from 'react'
import { layoutText, type DialogFile, type DialogInput } from '@/core/format'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { useItemTooltip } from '@/minecraft/item/ItemTooltip'
import { cx } from '@/ui/cx'
import { glyphAdvancesOf } from '@/minecraft/text/glyphs'
import { useWorkspace } from '@/state/providers'
import { MiniText, type GlyphMap } from '@/minecraft/text/MiniText'
import {
  arrangeButtons,
  DEFAULT_INPUT_WIDTH,
  DEFAULT_MESSAGE_WIDTH,
  type ButtonCell,
  type ListName,
} from './ops'
import type { DialogEntry } from './view'
import styles from './DialogPreview.module.css'

/** The picked entry, or null with none. */
export type DialogPick = DialogEntry | null

const WHITE = { color: '#ffffff' }

export function DialogPreview({
  model,
  listed,
  glyphs,
  picked,
  onPick,
  scale = 2,
}: {
  model: DialogFile
  /** Dialogs a dialog_list names, for their button labels. */
  listed: Record<string, DialogFile | undefined>
  glyphs: GlyphMap
  picked: DialogPick
  onPick: (pick: DialogPick) => void
  scale?: number
}) {
  const advances = useWorkspace((s) => s.glyphAdvances)
  const tooltip = useItemTooltip()
  const glyphAdvances = useMemo(() => glyphAdvancesOf(glyphs), [glyphs])
  const px = (n: number) => n * scale
  const { grid, footer } = arrangeButtons(model, listed)
  const isPicked = (list: ListName, index: number) =>
    picked?.list === list && picked.index === index
  const pickable = (list: ListName, index: number, label: string) => ({
    className: cx(styles.element, isPicked(list, index) && styles.picked),
    'aria-label': label,
    'aria-pressed': isPicked(list, index),
    onClick: () => onPick({ list, index }),
  })

  const button = (cell: ButtonCell, key: string) => {
    const style: CSSProperties = { width: px(cell.width), height: px(20) }
    const label = <MiniText text={cell.label} glyphs={glyphs} scale={scale} base={WHITE} />
    return cell.index === null ? (
      <span key={key} className={cx(styles.button, styles.listed)} style={style}>
        {label}
      </span>
    ) : (
      <button
        key={key}
        type="button"
        style={style}
        {...pickable('buttons', cell.index, `Button ${model.buttons?.[cell.index]?.key}`)}
      >
        <span className={styles.button}>{label}</span>
      </button>
    )
  }

  return (
    <div className={styles.screen} role="region" aria-label="Dialog preview" style={{ gap: px(6) }}>
      <div className={styles.title} style={{ marginTop: px(8) }}>
        <MiniText text={model.title} glyphs={glyphs} scale={scale} base={WHITE} />
      </div>
      <div className={styles.column} style={{ gap: px(6) }}>
        {(model.body ?? []).map((part, index) => {
          if (part.type === 'message') {
            const width = part.width ?? DEFAULT_MESSAGE_WIDTH
            return (
              <button
                key={`b${index}`}
                type="button"
                style={{ width: px(width) }}
                {...pickable('body', index, `Message ${index + 1}`)}
              >
                <MiniText
                  text={part.text}
                  lines={layoutText(part.text, width, advances, glyphAdvances)}
                  glyphs={glyphs}
                  scale={scale}
                  base={WHITE}
                  align="center"
                />
              </button>
            )
          }
          return (
            <button
              key={`b${index}`}
              type="button"
              {...pickable('body', index, `Item ${index + 1}`)}
              {...tooltip.handlers(part.showTooltip === false ? undefined : part.item)}
            >
              <span className={styles.item}>
                <ItemIcon item={part.item} size={px(part.width ?? 16)} />
                {part.description && (
                  <MiniText text={part.description} glyphs={glyphs} scale={scale} base={WHITE} />
                )}
              </span>
            </button>
          )
        })}
        {(model.inputs ?? []).map((input, index) => (
          <button
            key={`i${index}`}
            type="button"
            {...pickable('inputs', index, `Input ${input.key}`)}
          >
            <InputWidget input={input} glyphs={glyphs} scale={scale} />
          </button>
        ))}
        {grid.map((row, r) => (
          <div key={`g${r}`} className={styles.row} style={{ gap: px(2) }}>
            {row.map((cell, c) => button(cell, `g${r}-${c}`))}
          </div>
        ))}
      </div>
      {footer.length > 0 && (
        <div className={styles.row} style={{ gap: px(8), marginBottom: px(8) }}>
          {footer.map((cell, c) => button(cell, `f${c}`))}
        </div>
      )}
      {tooltip.tooltip}
    </div>
  )
}

function InputWidget({
  input,
  glyphs,
  scale,
}: {
  input: DialogInput
  glyphs: GlyphMap
  scale: number
}) {
  const px = (n: number) => n * scale
  const label = input.label ?? input.key
  const text = (value: string) => (
    <MiniText text={value} glyphs={glyphs} scale={scale} base={WHITE} />
  )
  switch (input.type) {
    case 'text': {
      const lines = Math.max(1, input.lines ?? 1)
      return (
        <span className={styles.input} style={{ width: px(input.width ?? DEFAULT_INPUT_WIDTH) }}>
          {input.labelVisible !== false && text(label)}
          <span className={styles.textbox} style={{ height: px(lines > 1 ? lines * 9 + 8 : 20) }}>
            {input.initial ? text(input.initial) : null}
          </span>
        </span>
      )
    }
    case 'boolean':
      return (
        <span className={cx(styles.input, styles.inputRow)}>
          <span className={styles.checkbox} style={{ width: px(17), height: px(17) }}>
            {input.initial ? '✓' : ''}
          </span>
          {text(label)}
        </span>
      )
    case 'single_option': {
      const option = input.options.find((it) => it.initial) ?? input.options[0]
      return (
        <span
          className={styles.button}
          style={{ width: px(input.width ?? DEFAULT_INPUT_WIDTH), height: px(20) }}
        >
          {text(`${label}: ${option?.label ?? option?.id ?? ''}`)}
        </span>
      )
    }
    case 'number_range': {
      const value = input.initial ?? (input.start + input.end) / 2
      const fraction =
        input.end > input.start ? (value - input.start) / (input.end - input.start) : 0
      return (
        <span
          className={styles.slider}
          style={{ width: px(input.width ?? DEFAULT_INPUT_WIDTH), height: px(20) }}
        >
          <span
            className={styles.sliderHandle}
            style={{
              left: `calc(${Math.min(1, Math.max(0, fraction)) * 100}% - ${px(4)}px)`,
              width: px(8),
            }}
          />
          {text(`${label}: ${Math.round(value * 100) / 100}`)}
        </span>
      )
    }
  }
}
