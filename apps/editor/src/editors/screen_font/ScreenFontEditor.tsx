/**
 * A screen font (`screen_fonts/<id>/font.json`, docs/format/screen-font.md): what screens write text in. A pixels
 * font is drawn here, a glyph at a time on a grid (click a pixel to turn it on or off); a bitmap font is a picture
 * of a grid of characters beside it, drawn with the grid over it; a TrueType font is a file the server draws at a
 * size. Each shows a sample line as it will look.
 */
import { useState } from 'react'
import { setKey } from '@/core/draft'
import type { ScreenFontFile, ScreenFontType } from '@/core/format'
import { dirname } from '@/core/paths'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { useApp } from '@/state/providers'
import { Button, IconButton } from '@/ui/Button'
import { ask } from '@/ui/dialogs'
import { CheckField, NumberField, Section, SelectField, TextField } from '@/ui/fields'
import { Hint } from '@/ui/text'
import {
  addGlyph,
  glyphOrder,
  heightOf,
  INK,
  removeGlyph,
  setHeight,
  setPixel,
  setType,
  setWidth,
} from './ops'
import styles from './ScreenFontEditor.module.css'

const TYPES: { value: ScreenFontType; label: string }[] = [
  { value: 'pixels', label: 'Pixels, drawn here' },
  { value: 'bitmap', label: 'Bitmap picture' },
  { value: 'truetype', label: 'TrueType font' },
]

export function ScreenFontEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<ScreenFontFile>(path)
  const { backend } = useApp()
  const folder = dirname(path)
  const id = folder.split('/').pop() ?? ''
  const [picked, setPicked] = useState<string | null>(null)
  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />
  const order = glyphOrder(model)
  const glyph = picked && model.glyphs?.[picked] ? picked : (order[0] ?? null)
  const rows = glyph ? (model.glyphs?.[glyph] ?? []) : []

  const add = async () => {
    const char = await ask.prompt({
      title: 'Add a character',
      label: 'Character',
      icon: 'font',
      validate: (value) =>
        [...value].length !== 1
          ? 'One character'
          : model.glyphs?.[value]
            ? `${value} is drawn already`
            : null,
    })
    if (!char) return
    edit((d) => addGlyph(d, char, rows[0]?.length ?? 5))
    setPicked(char)
  }

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        {model.type === 'pixels' && glyph ? (
          <div className={styles.stage}>
            <div
              className={styles.grid}
              role="grid"
              aria-label={`The pixels of ${glyph}`}
              style={{ gridTemplateColumns: `repeat(${rows[0]?.length ?? 1}, 24px)` }}
            >
              {rows.flatMap((row, y) =>
                [...row].map((cell, x) => (
                  <button
                    key={`${x},${y}`}
                    type="button"
                    role="gridcell"
                    aria-label={`${glyph} ${x},${y}`}
                    aria-pressed={cell === INK}
                    className={cell === INK ? `${styles.pixel} ${styles.ink}` : styles.pixel}
                    onClick={() => edit((d) => setPixel(d, glyph, x, y, cell !== INK))}
                  />
                )),
              )}
            </div>
            <Sample file={model} />
          </div>
        ) : model.type === 'bitmap' && model.file ? (
          <div className={styles.stage}>
            <div className={styles.picture}>
              <img
                src={backend.projectFileUrl(`${folder}/${model.file}`)}
                alt={`The characters of ${id}`}
              />
            </div>
            <Hint>
              Each cell of the grid is a character, as wide as its opaque columns, in the order
              chars lists them.
            </Hint>
          </div>
        ) : (
          <Hint>
            {model.type === 'truetype'
              ? `The server draws ${model.file ?? 'its font file'} at ${model.size ?? 16} pixels.`
              : 'Add a character to draw it.'}
          </Hint>
        )}
      </Stage>
      <InspectorPanel>
        <Section title="Font" icon="font">
          <SelectField<ScreenFontType>
            label="Type"
            dataPath="type"
            value={model.type}
            options={TYPES}
            onChange={(value) => edit((d) => setType(d, value))}
          />
          {model.type !== 'pixels' && (
            <TextField
              label={model.type === 'bitmap' ? 'Picture' : 'Font file'}
              dataPath="file"
              value={model.file}
              placeholder={model.type === 'bitmap' ? 'font.png' : 'font.ttf'}
              onChange={(value) => edit((d) => setKey(d, 'file', value.trim() || undefined))}
            />
          )}
          {model.type === 'bitmap' && (
            <TextField
              label="Characters, a row each"
              dataPath="chars"
              value={(model.chars ?? []).join('\n')}
              multiline
              onChange={(value) =>
                edit((d) => setKey(d, 'chars', value ? value.split('\n') : undefined))
              }
            />
          )}
          {model.type === 'truetype' && (
            <>
              <NumberField
                label="Size (pixels)"
                dataPath="size"
                value={model.size}
                placeholder="16"
                step={1}
                min={4}
                onChange={(value) =>
                  edit((d) =>
                    setKey(d, 'size', value === undefined ? undefined : Math.round(value)),
                  )
                }
              />
              <CheckField
                label="Smooth edges"
                dataPath="antialias"
                value={model.antialias === true}
                onChange={(on) => edit((d) => setKey(d, 'antialias', on ? true : undefined))}
              />
            </>
          )}
          <NumberField
            label="Spacing"
            dataPath="spacing"
            value={model.spacing}
            placeholder="1"
            step={1}
            min={0}
            onChange={(value) =>
              edit((d) => setKey(d, 'spacing', value === undefined ? undefined : Math.round(value)))
            }
          />
          <NumberField
            label="Space width"
            dataPath="spaceWidth"
            value={model.spaceWidth}
            step={1}
            min={0}
            onChange={(value) =>
              edit((d) =>
                setKey(d, 'spaceWidth', value === undefined ? undefined : Math.round(value)),
              )
            }
          />
          <NumberField
            label="Line height"
            dataPath="lineHeight"
            value={model.lineHeight}
            step={1}
            min={1}
            onChange={(value) =>
              edit((d) =>
                setKey(d, 'lineHeight', value === undefined ? undefined : Math.round(value)),
              )
            }
          />
          <Hint>
            Scripts write in it with screen:text(x, y, text, {'{'} font = "{id}" {'}'}).
          </Hint>
        </Section>
        {model.type === 'pixels' && (
          <Section
            title="Characters"
            icon="grid"
            actions={<IconButton icon="plus" label="Add a character" onClick={() => void add()} />}
          >
            <NumberField
              label="Height"
              dataPath="glyphs"
              value={heightOf(model)}
              step={1}
              min={1}
              onChange={(value) => edit((d) => setHeight(d, value ?? 1))}
            />
            {glyph && (
              <NumberField
                label={`Width of ${glyph}`}
                value={rows[0]?.length ?? 1}
                step={1}
                min={1}
                onChange={(value) => edit((d) => setWidth(d, glyph, value ?? 1))}
              />
            )}
            <div className={styles.glyphs} role="listbox" aria-label="Characters">
              {order.map((char) => (
                <button
                  key={char}
                  type="button"
                  role="option"
                  aria-selected={char === glyph}
                  className={char === glyph ? `${styles.glyph} ${styles.picked}` : styles.glyph}
                  onClick={() => setPicked(char)}
                >
                  {char}
                </button>
              ))}
            </div>
            {glyph && (
              <Button
                icon="trash"
                variant="ghost"
                size="small"
                onClick={() => edit((d) => removeGlyph(d, glyph))}
              >
                Remove {glyph}
              </Button>
            )}
          </Section>
        )}
      </InspectorPanel>
    </EditorScreen>
  )
}

/** Every glyph of a pixels font in a row, as a screen would draw them, two screen pixels to one. */
function Sample({ file }: { file: ScreenFontFile }) {
  const glyphs = glyphOrder(file)
  const spacing = file.spacing ?? 1
  const height = heightOf(file)
  return (
    <div className={styles.sample} aria-label="Sample" data-testid="font-sample">
      {glyphs.map((char) => {
        const rows = file.glyphs?.[char] ?? []
        const width = rows[0]?.length ?? 0
        return (
          <svg
            key={char}
            width={(width + spacing) * 3}
            height={height * 3}
            viewBox={`0 0 ${width + spacing} ${height}`}
            shapeRendering="crispEdges"
          >
            {rows.flatMap((row, y) =>
              [...row].map((cell, x) =>
                cell === INK ? <rect key={`${x},${y}`} x={x} y={y} width={1} height={1} /> : null,
              ),
            )}
          </svg>
        )
      })}
    </div>
  )
}
