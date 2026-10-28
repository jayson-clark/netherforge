/**
 * A project sky (`skies/<id>.json`, docs/format/sky.md): the colours scripts turn a world's (or a player's) sky,
 * fog, clouds and water to with `world:set_sky`. The stage draws them as a strip of sky over land fading into fog
 * over water; the inspector edits them and how many steps a fade takes. A colour left empty stays each biome's own.
 */
import type { SkyFile } from '@/core/format'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { CheckField, ColorField, NumberField, Section, TextField } from '@/ui/fields'
import { Hint } from '@/ui/text'
import styles from './SkyEditor.module.css'

type ColorKey = 'sky' | 'fog' | 'clouds' | 'water' | 'waterFog' | 'light'

/** `alpha`: written `#rrggbb` or `#aarrggbb` (the game's clouds), so a text field rather than a colour picker. */
const COLORS: { key: ColorKey; label: string; overworld: string; alpha?: boolean }[] = [
  { key: 'sky', label: 'Sky', overworld: '#78a7ff' },
  { key: 'fog', label: 'Fog', overworld: '#c0d8ff' },
  { key: 'clouds', label: 'Clouds', overworld: '#ccffffff', alpha: true },
  { key: 'water', label: 'Water', overworld: '#3f76e4' },
  { key: 'waterFog', label: 'Fog under water', overworld: '#050533' },
  { key: 'light', label: 'Light on the land', overworld: '#ffffff' },
]

/** The land the preview's light falls on: the plains' grass, which the light multiplies as the game's does. */
const GRASS = '#79c05a'

/** `#aarrggbb` as CSS's `#rrggbbaa`; anything else as it is. */
function css(color: string): string {
  return /^#[0-9a-fA-F]{8}$/.test(color) ? `#${color.slice(3)}${color.slice(1, 3)}` : color
}

/** The default number of steps a fade takes, as format's `SkyFile.DEFAULT_FADE`. */
const DEFAULT_FADE = 3

export function SkyEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<SkyFile>(path)
  const id = path.split('/')[1]?.replace(/\.json$/, '') ?? ''
  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />
  const shown = (key: ColorKey) => css(model[key] ?? COLORS.find((it) => it.key === key)!.overworld)
  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div
          className={styles.preview}
          role="img"
          aria-label="The sky's colours"
          data-testid="sky-preview"
        >
          <div
            className={styles.sky}
            style={{ background: `linear-gradient(${shown('sky')}, ${shown('fog')})` }}
          >
            <div className={styles.cloud} style={{ background: shown('clouds') }} />
          </div>
          <div
            className={styles.land}
            style={{
              background: `linear-gradient(${shown('light')}, ${shown('light')}), ${GRASS}`,
            }}
          />
          <div
            className={styles.water}
            style={{ background: `linear-gradient(${shown('water')}, ${shown('waterFog')})` }}
          />
        </div>
      </Stage>
      <InspectorPanel>
        <Section title="Colours" icon="palette">
          {COLORS.map(({ key, label, overworld, alpha }) =>
            alpha ? (
              <TextField
                key={key}
                label={label}
                dataPath={key}
                value={model[key]}
                placeholder={overworld}
                onChange={(value) =>
                  edit((d) => {
                    const trimmed = value.trim()
                    if (trimmed) d[key] = trimmed
                    else delete d[key]
                  })
                }
              />
            ) : (
              <ColorField
                key={key}
                label={label}
                dataPath={key}
                value={model[key]}
                placeholder={overworld}
                onChange={(value) =>
                  edit((d) => {
                    if (value) d[key] = value
                    else delete d[key]
                  })
                }
              />
            ),
          )}
          <Hint>
            A colour left empty stays each biome's own. Scripts set it with world:set_sky("{id}") or
            player:set_sky("{id}").
          </Hint>
        </Section>
        <Section title="Night" icon="moon">
          <CheckField
            label="Holds its sky and fog at night"
            dataPath="darkens"
            value={model.darkens === false}
            onChange={(held) =>
              edit((d) => {
                if (held) d.darkens = false
                else delete d.darkens
              })
            }
          />
          <Hint>
            Night darkens the sky and the fog to near black, as it does the game's. Held, they stay
            the sky's colours all night (Minecraft 26.1 and later). The light on the land is tinted
            either way, and night still dims it.
          </Hint>
        </Section>
        <Section title="Fading" icon="timer">
          <NumberField
            label="Steps"
            dataPath="fade"
            value={model.fade}
            placeholder={String(DEFAULT_FADE)}
            step={1}
            min={1}
            onChange={(value) =>
              edit((d) => {
                if (value === undefined) delete d.fade
                else d.fade = Math.round(value)
              })
            }
          />
          <Hint>
            A fade moves through this many steps between each biome's colours and these. More is
            smoother, and more for players to load as they join. A new or changed sky needs a
            restart.
          </Hint>
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}
