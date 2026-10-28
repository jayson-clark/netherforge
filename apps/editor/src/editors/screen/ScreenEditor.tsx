/**
 * A screen (`screens/<id>/screen.json`, docs/format/screen.md): a wall of maps scripts draw on and play videos on.
 * The stage draws its maps as the wall a player faces, in its background colour, each with its column and row
 * (what `screen:item(column, row)` hands out); the inspector edits its size, where it hangs, who it's drawn for,
 * the video it plays from the start, and its script, which draws it.
 */
import { setKey } from '@/core/draft'
import {
  newScript,
  newSiblingFile,
  type ScreenFacing,
  type ScreenFile,
  type ScreenViewers,
} from '@/core/format'
import { dirname } from '@/core/paths'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { ScriptField } from '@/editors/shared/ScriptField'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { useWorkspace } from '@/state/providers'
import {
  CheckField,
  ColorField,
  NumberField,
  Row,
  Section,
  SelectField,
  TextField,
} from '@/ui/fields'
import { Hint } from '@/ui/text'
import {
  DEFAULT_CLICK_RANGE,
  DEFAULT_RANGE,
  FACINGS,
  NEW_PLACE,
  TILE,
  setPlace,
  setPlaceField,
  setSize,
  setVideo,
} from './ops'
import styles from './ScreenEditor.module.css'

const NO_VIDEOS: string[] = []

export function ScreenEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<ScreenFile>(path)
  const videos = useWorkspace((s) => s.outline?.resources.video ?? NO_VIDEOS)
  const folder = dirname(path)
  const id = folder.split('/').pop() ?? ''
  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />
  const width = Math.max(1, model.width)
  const height = Math.max(1, model.height)
  const place = model.place
  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.wall}>
          <div
            className={styles.maps}
            role="img"
            aria-label={`${width} by ${height} maps`}
            data-testid="screen-preview"
            style={{
              gridTemplateColumns: `repeat(${width}, 1fr)`,
              aspectRatio: `${width} / ${height}`,
            }}
          >
            {Array.from({ length: width * height }, (_, i) => (
              <div
                key={i}
                className={model.background ? styles.map : `${styles.map} ${styles.clear}`}
                style={model.background ? { background: model.background } : undefined}
              >
                <span className={styles.label}>
                  {i % width}, {Math.floor(i / width)}
                </span>
              </div>
            ))}
          </div>
          <p className={styles.caption}>
            {width * TILE} by {height * TILE} pixels
            {place
              ? `, hanging in ${place.world} at ${place.x} ${place.y} ${place.z}, facing ${place.facing}`
              : ', hanging nowhere until a script places it'}
          </p>
        </div>
      </Stage>
      <InspectorPanel>
        <Section title="Size" icon="grid">
          <NumberField
            label="Maps across"
            dataPath="width"
            value={model.width}
            step={1}
            min={1}
            onChange={(value) => edit((d) => setSize(d, 'width', value ?? 1))}
          />
          <NumberField
            label="Maps down"
            dataPath="height"
            value={model.height}
            step={1}
            min={1}
            onChange={(value) => edit((d) => setSize(d, 'height', value ?? 1))}
          />
          <ColorField
            label="Background"
            dataPath="background"
            value={model.background}
            placeholder="clear"
            onChange={(value) => edit((d) => setKey(d, 'background', value || undefined))}
          />
          <Hint>
            Each map is 128 by 128 pixels. Left clear, the frames' empty maps show through until
            it's drawn on.
          </Hint>
        </Section>
        <Section
          title="Where it hangs"
          icon="map"
          enabled={place !== undefined}
          onToggle={(on) => edit((d) => setPlace(d, on ? { ...NEW_PLACE } : null))}
        >
          {place && (
            <>
              <TextField
                label="World"
                dataPath="place.world"
                value={place.world}
                onChange={(value) =>
                  edit((d) => setPlaceField(d, 'world', value.trim() || 'world'))
                }
              />
              {(['x', 'y', 'z'] as const).map((axis) => (
                <NumberField
                  key={axis}
                  label={axis.toUpperCase()}
                  dataPath={`place.${axis}`}
                  value={place[axis]}
                  step={1}
                  onChange={(value) => edit((d) => setPlaceField(d, axis, Math.round(value ?? 0)))}
                />
              ))}
              <SelectField<ScreenFacing>
                label="Facing"
                dataPath="place.facing"
                value={place.facing}
                options={FACINGS}
                onChange={(value) => edit((d) => setPlaceField(d, 'facing', value))}
              />
            </>
          )}
          <Hint>
            The block is the air in front of the wall where its top left map hangs; the rest go
            right and down as a player facing it sees them. Facing is the way out of the wall. The
            server hangs its frames there and keeps them there; a script can move it with
            screen:place.
          </Hint>
        </Section>
        <Section title="Who sees it" icon="eye">
          <SelectField<ScreenViewers>
            label="Drawn for"
            dataPath="viewers"
            value={model.viewers ?? 'near'}
            options={[
              { value: 'near', label: 'Everyone near it' },
              { value: 'script', label: 'Those a script shows it to' },
            ]}
            onChange={(value) =>
              edit((d) => setKey(d, 'viewers', value === 'near' ? undefined : value))
            }
          />
          <NumberField
            label="Range (blocks)"
            dataPath="range"
            value={model.range}
            placeholder={String(DEFAULT_RANGE)}
            step={1}
            min={1}
            onChange={(value) =>
              edit((d) => setKey(d, 'range', value === undefined ? undefined : Math.round(value)))
            }
          />
          <NumberField
            label="Click range (blocks)"
            dataPath="clickRange"
            value={model.clickRange}
            placeholder={String(DEFAULT_CLICK_RANGE)}
            step={1}
            min={0}
            onChange={(value) => edit((d) => setKey(d, 'clickRange', value))}
          />
          <CheckField
            label="Glowing frames"
            dataPath="glowing"
            value={model.glowing !== false}
            onChange={(on) => edit((d) => setKey(d, 'glowing', on ? undefined : false))}
          />
          <Hint>
            Glowing frames draw it at full brightness, day or night. Past the game's reach, a swing
            of the arm aimed at it is a click, up to its click range.
          </Hint>
        </Section>
        <Section
          title="Video"
          icon="video"
          enabled={model.video !== undefined}
          onToggle={(on) => edit((d) => setVideo(d, on ? (videos[0] ?? 'video') : null))}
        >
          {model.video && (
            <>
              <Row label="Plays">
                <select
                  aria-label="Plays"
                  data-path="video.video"
                  value={model.video.video}
                  onChange={(e) => edit((d) => setVideo(d, e.target.value))}
                >
                  {[...new Set([model.video.video, ...videos])].map((it) => (
                    <option key={it} value={it}>
                      {it}
                    </option>
                  ))}
                </select>
              </Row>
              <CheckField
                label="Loops"
                dataPath="video.loop"
                value={model.video.loop !== false}
                onChange={(on) =>
                  edit((d) => d.video && setKey(d.video, 'loop', on ? undefined : false))
                }
              />
              <NumberField
                label="Volume"
                dataPath="video.volume"
                value={model.video.volume}
                placeholder="1"
                step={0.1}
                min={0}
                onChange={(value) => edit((d) => d.video && setKey(d.video, 'volume', value))}
              />
            </>
          )}
          <Hint>
            A video it plays from the start, whenever the server loads it. Scripts play others with
            screen:play.
          </Hint>
        </Section>
        <Section title="Script" icon="code">
          <ScriptField
            folder={folder}
            script={model.script}
            dataPath="script"
            suggestedName="script.lua"
            template={newScript('screen', '')}
            siblingTemplate={newSiblingFile('screen')}
            onChange={(script) => edit((d) => setKey(d, 'script', script))}
          />
          <Hint>
            Its script draws it (this:clear, this:text, this:image, …) each time the server loads
            it, and hears its clicks: this:on("click", …). Others reach it with nf.screens.get("{id}
            ").
          </Hint>
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}
