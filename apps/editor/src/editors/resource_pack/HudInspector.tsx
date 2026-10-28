/** The HUD's own entries (bars, rows of icons, lines of text) and which of the game's HUD elements the pack hides or redraws. */
import { setKey } from '@/core/draft'
import {
  HUD_LIMITS,
  HudAlignValues,
  HudElementValues,
  type HudAlign,
  type HudBarDef,
  type HudIconsDef,
  type ResourcePackFile,
} from '@/core/format'
import { ID_PATTERN } from '@/core/paths'
import { useApp } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import {
  CheckField,
  Check,
  FieldGroup,
  NumberField,
  Row,
  Section,
  SelectField,
  TextField,
} from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { Hint } from '@/ui/text'
import { fieldPath } from '@/editors/shared/focus'
import {
  hudKindOf,
  removeHudElement,
  renameHudElement,
  renameHudIcon,
  setHudHidden,
  setHudIcon,
  setHudReplace,
} from './ops'
import { type GameTextures } from '@/minecraft/client/useGameTextures'
import { HUD_LABELS, type Pick } from './shared'

/** The game's own HUD: which elements the pack hides, and which sprites it draws instead. */
export function GameHudSection({
  model,
  textures,
  game,
  edit,
}: {
  model: ResourcePackFile
  textures: string[]
  game: GameTextures | null
  edit: (recipe: (draft: ResourcePackFile) => void) => void
}) {
  const hidden = model.hud?.hide ?? []
  const replaced = Object.entries(model.hud?.replace ?? {}).sort(([a], [b]) =>
    a < b ? -1 : a > b ? 1 : 0,
  )
  const sprites = game?.sprites ?? []
  const textureOptions = (current: string) => [
    ...textures.map((it) => ({ value: it, label: it })),
    ...(textures.includes(current) ? [] : [{ value: current, label: `${current} (missing)` }]),
  ]
  return (
    <Section title="The game's HUD" icon="layers">
      <FieldGroup header="Hidden for every player" data-path="hud.hide">
        {HudElementValues.map((element) => (
          <Check
            key={element}
            label={element}
            checked={hidden.includes(element)}
            onChange={(on) => edit((draft) => setHudHidden(draft, element, on, HudElementValues))}
          />
        ))}
      </FieldGroup>
      <FieldGroup header="Sprites drawn instead" data-path="hud.replace">
        {replaced.map(([sprite, texture]) => (
          <Row key={sprite} label={sprite}>
            <select
              aria-label={`Sprite ${sprite}`}
              data-path={fieldPath(['hud', 'replace', sprite])}
              value={texture}
              onChange={(event) =>
                edit((draft) => setHudReplace(draft, sprite, event.target.value))
              }
            >
              {textureOptions(texture).map((it) => (
                <option key={it.value} value={it.value}>
                  {it.label}
                </option>
              ))}
            </select>
            <IconButton
              icon="trash"
              label={`Draw the game's ${sprite} again`}
              onClick={() => edit((draft) => setHudReplace(draft, sprite, undefined))}
            />
          </Row>
        ))}
        <Button
          size="small"
          icon="plus"
          disabled={textures.length === 0}
          onClick={async () => {
            const sprite = await ask.prompt({
              title: 'Redraw a HUD sprite',
              label: 'Sprite (its path under the client’s textures/gui/sprites/)',
              initial: 'hud/',
              confirmLabel: 'Redraw',
              suggestions: sprites,
              validate: (value) =>
                !/^[a-z0-9_]+(\/[a-z0-9_]+)*$/.test(value)
                  ? 'A sprite name: hud/hotbar, hud/heart/full…'
                  : model.hud?.replace?.[value]
                    ? 'The pack redraws it already'
                    : null,
            })
            if (sprite) edit((draft) => setHudReplace(draft, sprite, textures[0]!))
          }}
        >
          Redraw a sprite
        </Button>
      </FieldGroup>
      <Hint>
        Hiding and redrawing apply to every player. Draw a sprite at the game&apos;s own pixel size.
      </Hint>
    </Section>
  )
}

/** One of the pack's HUD elements: a bar, a row of icons or a line of text, which scripts fill per player. */
export function HudInspector({
  pack,
  hudKey,
  model,
  textures,
  edit,
  onPick,
}: {
  pack: string
  hudKey: string
  model: ResourcePackFile
  textures: string[]
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onPick: (pick: Pick) => void
}) {
  const { workspace } = useApp()
  const kind = hudKindOf(model, hudKey)!
  const def = model.hud![kind]![hudKey]!
  const at = (...rest: string[]) => fieldPath(['hud', kind, hudKey, ...rest])
  const editDef = <D,>(recipe: (draft: D) => void) =>
    edit((draft) => {
      const target = draft.hud?.[kind]?.[hudKey]
      if (target) recipe(target as D)
    })
  const set = (key: string, value: unknown) =>
    editDef<Record<string, unknown>>((d) => setKey(d, key, value))
  const whole = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))
  const textureSelect = (
    label: string,
    key: string,
    value: string | undefined,
    optional: boolean,
  ) => (
    <SelectField
      key={key}
      label={label}
      dataPath={at(key)}
      value={value ?? ''}
      options={[
        ...(optional || !value ? [{ value: '', label: optional ? '(none)' : '(choose)' }] : []),
        ...textures.map((it) => ({ value: it, label: it })),
        ...(value && !textures.includes(value) ? [{ value, label: `${value} (missing)` }] : []),
      ]}
      onChange={(next) => set(key, next || undefined)}
    />
  )
  const place = (y: boolean) => (
    <>
      <NumberField
        label="X"
        dataPath={at('x')}
        value={(def as { x: number }).x}
        step={1}
        onChange={(value) =>
          value !== undefined &&
          set('x', Math.max(-HUD_LIMITS.maxX, Math.min(HUD_LIMITS.maxX, Math.round(value))))
        }
      />
      {y && (
        <NumberField
          label="Y"
          dataPath={at('y')}
          value={(def as { y: number }).y}
          step={1}
          min={0}
          onChange={(value) =>
            value !== undefined && set('y', Math.min(HUD_LIMITS.maxY, Math.round(value)))
          }
        />
      )}
      <SelectField<HudAlign | ''>
        label="Align"
        dataPath={at('align')}
        value={(def as { align?: HudAlign }).align ?? ''}
        options={[
          { value: '', label: 'left (default)' },
          ...HudAlignValues.filter((it) => it !== 'left').map((it) => ({ value: it, label: it })),
        ]}
        onChange={(value) => set('align', value || undefined)}
      />
    </>
  )
  return (
    <Section
      title={`HUD ${HUD_LABELS[kind]} ${pack}/${hudKey}`}
      actions={
        <IconButton
          icon="trash"
          label={`Delete HUD element ${hudKey}`}
          onClick={() => {
            edit((draft) => removeHudElement(draft, hudKey))
            onPick(null)
          }}
        />
      }
    >
      <TextField
        label="Key"
        value={hudKey}
        onChange={(next) => {
          if (!ID_PATTERN.test(next) || hudKindOf(model, next)) {
            workspace
              .getState()
              .notify('error', `"${next}" isn't a free key (lowercase letters, digits and _)`)
            return
          }
          edit((draft) => renameHudElement(draft, hudKey, next))
          onPick({ kind: 'hud', key: next })
        }}
      />
      {kind === 'bars' && (
        <>
          {textureSelect('Full', 'full', (def as HudBarDef).full, false)}
          {textureSelect('Half', 'half', (def as HudBarDef).half, true)}
          {textureSelect('Empty', 'empty', (def as HudBarDef).empty, false)}
          <NumberField
            label="Segments"
            dataPath={at('segments')}
            value={(def as HudBarDef).segments}
            step={1}
            min={1}
            onChange={(value) =>
              value !== undefined &&
              set('segments', Math.min(HUD_LIMITS.maxSegments, Math.round(value)))
            }
          />
          <NumberField
            label="Gap"
            dataPath={at('gap')}
            value={(def as HudBarDef).gap}
            placeholder="0"
            step={1}
            min={0}
            onChange={(value) => set('gap', whole(value) || undefined)}
          />
          <CheckField
            label="Fills from the right"
            dataPath={at('reverse')}
            value={(def as HudBarDef).reverse === true}
            onChange={(on) => set('reverse', on || undefined)}
          />
          {place(true)}
        </>
      )}
      {kind === 'icons' && (
        <>
          <FieldGroup header="Icons" data-path={at('icons')}>
            {Object.entries((def as HudIconsDef).icons).map(([icon, texture]) => (
              <Row key={icon} label="">
                <input
                  aria-label={`Icon ${icon} key`}
                  defaultValue={icon}
                  key={icon}
                  onBlur={(event) => {
                    const next = event.target.value.trim()
                    if (next === icon) return
                    if (!ID_PATTERN.test(next) || (def as HudIconsDef).icons[next]) {
                      event.target.value = icon
                      return
                    }
                    edit((draft) => renameHudIcon(draft, hudKey, icon, next))
                  }}
                />
                <select
                  aria-label={`Icon ${icon}`}
                  data-path={at('icons', icon)}
                  value={texture}
                  onChange={(event) =>
                    edit((draft) => setHudIcon(draft, hudKey, icon, event.target.value))
                  }
                >
                  {[...textures, ...(textures.includes(texture) ? [] : [texture])].map((it) => (
                    <option key={it} value={it}>
                      {it}
                    </option>
                  ))}
                </select>
                <IconButton
                  icon="trash"
                  label={`Remove icon ${icon}`}
                  disabled={Object.keys((def as HudIconsDef).icons).length <= 1}
                  onClick={() => edit((draft) => setHudIcon(draft, hudKey, icon, undefined))}
                />
              </Row>
            ))}
            <Button
              size="small"
              icon="plus"
              disabled={textures.length === 0}
              onClick={async () => {
                const icon = await ask.prompt({
                  title: 'New icon',
                  label: 'Key (what scripts show it by)',
                  initial: '',
                  confirmLabel: 'Add',
                  validate: (value) =>
                    !ID_PATTERN.test(value)
                      ? 'Lowercase letters, digits and _'
                      : (def as HudIconsDef).icons[value]
                        ? 'That icon exists'
                        : null,
                })
                if (icon) edit((draft) => setHudIcon(draft, hudKey, icon, textures[0]!))
              }}
            >
              Add icon
            </Button>
          </FieldGroup>
          <NumberField
            label="Gap"
            dataPath={at('gap')}
            value={(def as HudIconsDef).gap}
            placeholder={String(HUD_LIMITS.iconGap)}
            step={1}
            min={0}
            onChange={(value) => set('gap', whole(value))}
          />
          {place(true)}
        </>
      )}
      {kind === 'texts' && place(false)}
      <Hint>
        GUI pixels: x from the middle of the screen ({-HUD_LIMITS.maxX} to {HUD_LIMITS.maxX}), y up
        from the bottom (0 to {HUD_LIMITS.maxY}). The hotbar&apos;s top is at 22, hearts at 30.
        {kind === 'texts' && ' A line of text is drawn on the action bar’s own line.'}
      </Hint>
    </Section>
  )
}
