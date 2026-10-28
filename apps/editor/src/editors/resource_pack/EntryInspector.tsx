/** The inspector for a pack.json entry (a skin, glyph, item model, tooltip, equipment or block look), with its preview. */
import { setKey } from '@/core/draft'
import {
  GLYPH_DEFAULTS,
  MENU_ROWS,
  MenuTypeValues,
  ItemModelParentValues,
  SKIN_DEFAULTS,
  type ResourcePackFile,
  type SkinDef,
  type TooltipDef,
} from '@/core/format'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { WindowFrame, useSkin } from '@/minecraft/window/WindowFrame'
import { ID_PATTERN, resourcePackTexturePath } from '@/core/paths'
import { useApp } from '@/state/providers'
import { MiniText, TextPreview } from '@/minecraft/text/MiniText'
import { Datalist, NumberField, Row, Section, SelectField, TextField } from '@/ui/fields'
import { IconButton } from '@/ui/Button'
import { Hint } from '@/ui/text'
import { removeEntry, renameEntry, TEXTURE_FIELDS, type EntryDef, type EntryKind } from './ops'
import {
  useCompiledResourcePacks,
  useResourcePackFiles,
  useResourcePackModels,
  useProjectFileUrl,
} from '@/state/useResourcePacks'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import styles from './ResourcePackEditor.module.css'
import { KIND_LABELS, type Pick } from './shared'

/** A texture field's label: `humanoidLeggings` → `Humanoid leggings`. */
const textureLabel = (key: string) =>
  key[0]!.toUpperCase() + key.slice(1).replace(/[A-Z]/g, (it) => ` ${it.toLowerCase()}`)

/** The `drawn` stages a text field writes: model files, comma-separated, in order; none is no `drawn`. */
const drawnFromText = (text: string) => {
  const stages = text
    .split(',')
    .map((it) => it.trim())
    .filter(Boolean)
  return stages.length > 0 ? stages : undefined
}

export function EntryInspector({
  pack,
  kind,
  entryKey,
  def,
  model,
  textures,
  edit,
  onPick,
}: {
  pack: string
  kind: EntryKind
  entryKey: string
  def: EntryDef
  model: ResourcePackFile
  textures: string[]
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onPick: (pick: Pick) => void
}) {
  const { workspace } = useApp()
  const packs = useResourcePackFiles()
  const packModels = useResourcePackModels()
  const at = (key: string) => `${kind}.${entryKey}.${key}`
  const editDef = <D extends EntryDef>(recipe: (draft: D) => void) =>
    edit((draft) => {
      const target = draft[kind]?.[entryKey]
      if (target) recipe(target as D)
    })
  const setDef = (key: string, value: unknown) =>
    editDef((d) => setKey(d as Record<string, unknown>, key, value))
  const textureField = (key: string, label: string, optional: boolean) => {
    const value = (def as Record<string, unknown>)[key] as string | undefined
    return (
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
        onChange={(next) => setDef(key, next || undefined)}
      />
    )
  }
  const one = KIND_LABELS[kind].one
  // An item drawn as a pack block has no texture of its own, and is held as a block.
  const block = kind === 'items' ? (def as { block?: string }).block : undefined
  const blockRefs = Object.entries(packs)
    .flatMap(([key, file]) => Object.keys(file.blocks ?? {}).map((entry) => `${key}/${entry}`))
    .sort()
  // An item or block drawn as a model file has no textures of its own in pack.json: the model names them.
  const shaped =
    kind === 'items' || kind === 'blocks' ? (def as { model?: string }).model : undefined
  const modelFiles = Object.keys(packModels[pack]?.files ?? {}).sort()

  return (
    <Section
      title={`${one[0]!.toUpperCase()}${one.slice(1)} ${pack}/${entryKey}`}
      actions={
        <IconButton
          icon="trash"
          label={`Delete ${one} ${entryKey}`}
          onClick={() => {
            edit((draft) => removeEntry(draft, kind, entryKey))
            onPick(null)
          }}
        />
      }
    >
      <TextField
        label="Key"
        value={entryKey}
        onChange={(next) => {
          if (!ID_PATTERN.test(next) || model[kind]?.[next]) {
            workspace
              .getState()
              .notify('error', `"${next}" isn't a free key (lowercase letters, digits and _)`)
            return
          }
          edit((draft) => renameEntry(draft, kind, entryKey, next))
          onPick({ kind, key: next })
        }}
      />
      {kind === 'items' && (
        <SelectField
          label="Drawn as block"
          dataPath={at('block')}
          value={block ?? ''}
          options={[
            { value: '', label: '(none: a texture)' },
            ...blockRefs.map((it) => ({ value: it, label: it })),
            ...(block && !blockRefs.includes(block)
              ? [{ value: block, label: `${block} (missing)` }]
              : []),
          ]}
          onChange={(next) =>
            editDef<{ block?: string; texture?: string; parent?: string }>((d) => {
              if (next) {
                d.block = next
                delete d.texture
                delete d.parent
              } else delete d.block
            })
          }
        />
      )}
      {(kind === 'items' || kind === 'blocks') && !block && (
        <SelectField
          label="Model file"
          dataPath={at('model')}
          value={shaped ?? ''}
          options={[
            {
              value: '',
              label: kind === 'items' ? '(none: a texture)' : '(none: a cube of textures)',
            },
            ...modelFiles.map((it) => ({ value: it, label: `models/${it}` })),
            ...(shaped && !modelFiles.includes(shaped)
              ? [{ value: shaped, label: `models/${shaped} (missing)` }]
              : []),
          ]}
          onChange={(next) =>
            editDef<Record<string, unknown>>((d) => {
              if (!next) {
                delete d.model
                return
              }
              d.model = next
              // A model's own textures draw it: the look keeps only what isn't a texture of the shape.
              for (const key of TEXTURE_FIELDS[kind]) if (key !== 'guiTexture') delete d[key]
              delete d.parent
            })
          }
        />
      )}
      {kind === 'items' && (
        <>
          <TextField
            label="Draw stages"
            dataPath={at('drawn')}
            value={((def as { drawn?: string[] }).drawn ?? []).join(', ')}
            placeholder="pull_0.json, pull_1.json"
            list="drawn-models"
            onChange={(text) => setDef('drawn', drawnFromText(text))}
          />
          <Datalist id="drawn-models" values={modelFiles} />
        </>
      )}
      {TEXTURE_FIELDS[kind]
        .filter((key) => !(block && key === 'texture'))
        .filter((key) => !(shaped && key !== 'guiTexture'))
        .map((key) =>
          textureField(
            key,
            key === 'guiTexture' ? 'GUI texture' : textureLabel(key),
            kind === 'tooltips' ||
              kind === 'equipment' ||
              kind === 'blocks' ||
              key === 'guiTexture',
          ),
        )}
      {(kind === 'skins' || kind === 'glyphs') && (
        <>
          <NumberField
            label="Height"
            dataPath={at('height')}
            value={(def as { height?: number }).height}
            placeholder={String(kind === 'skins' ? SKIN_DEFAULTS.height : GLYPH_DEFAULTS.height)}
            step={1}
            min={1}
            onChange={(value) =>
              setDef('height', value === undefined ? undefined : Math.round(value))
            }
          />
          <NumberField
            label="Ascent"
            dataPath={at('ascent')}
            value={(def as { ascent?: number }).ascent}
            placeholder={String(kind === 'skins' ? SKIN_DEFAULTS.ascent : GLYPH_DEFAULTS.ascent)}
            step={1}
            onChange={(value) =>
              setDef('ascent', value === undefined ? undefined : Math.round(value))
            }
          />
        </>
      )}
      {kind === 'skins' && (
        <SkinFields pack={pack} entryKey={entryKey} def={def as SkinDef} at={at} setDef={setDef} />
      )}
      {kind === 'glyphs' && <GlyphPreview pack={pack} entryKey={entryKey} />}
      {kind === 'items' && (
        <>
          {!block && !shaped && (
            <SelectField
              label="Held like"
              dataPath={at('parent')}
              value={(def as { parent?: string }).parent ?? ''}
              options={[
                { value: '', label: 'Default (a flat sprite)' },
                ...ItemModelParentValues.map((it) => ({ value: it, label: it })),
              ]}
              onChange={(value) => setDef('parent', value || undefined)}
            />
          )}
          <Row label="In a slot">
            <span className={styles.slotPreview}>
              <ItemIcon item={{ kind: '', itemModel: `${pack}/${entryKey}` }} size={64} />
            </span>
          </Row>
        </>
      )}
      {kind === 'tooltips' && <TooltipPreview pack={pack} def={def as TooltipDef} />}
      {kind === 'blocks' && (
        <Hint>
          The look of a block whose model is {pack}/{entryKey}: one texture for every face, or a
          top, a bottom and the sides. A face takes its own texture, then Side (for the four sides),
          then Texture.
        </Hint>
      )}
      {kind === 'equipment' && (
        <Hint>
          Worn by an item whose equipment is {pack}/{entryKey}. Give the layers its slot draws:
          humanoid for a helmet, chestplate or boots, humanoid leggings for leggings.
        </Hint>
      )}
    </Section>
  )
}

function SkinFields({
  pack,
  entryKey,
  def,
  at,
  setDef,
}: {
  pack: string
  entryKey: string
  def: SkinDef
  at: (key: string) => string
  setDef: (key: string, value: unknown) => void
}) {
  const skin = useSkin(`${pack}/${entryKey}`)
  const type = def.type ?? 'chest'
  return (
    <>
      <NumberField
        label="Offset"
        dataPath={at('offset')}
        value={def.offset}
        placeholder={String(SKIN_DEFAULTS.offset)}
        step={1}
        onChange={(value) => setDef('offset', value === undefined ? undefined : Math.round(value))}
      />
      <SelectField
        label="Drawn for"
        dataPath={at('type')}
        value={def.type ?? ''}
        options={[
          { value: '', label: '(any; preview a chest)' },
          ...MenuTypeValues.map((it) => ({ value: it, label: it })),
        ]}
        onChange={(value) => setDef('type', value || undefined)}
      />
      {type === 'chest' && (
        <SelectField
          label="Rows"
          dataPath={at('rows')}
          value={def.rows === undefined ? '' : String(def.rows)}
          options={[
            { value: '', label: `(${MENU_ROWS.default})` },
            ...Array.from({ length: MENU_ROWS.max }, (_, i) => ({
              value: String(i + 1),
              label: String(i + 1),
            })),
          ]}
          onChange={(value) => setDef('rows', value ? Number(value) : undefined)}
        />
      )}
      <div className={styles.skinPreview} aria-label="Skin preview">
        <WindowFrame
          type={type}
          rows={def.rows}
          title={undefined}
          skin={skin}
          scale={1.75}
          outline
          label="Skin over the window"
        />
        <Hint>
          Where the server draws it: the title starts at 8, 6; the prefix moves{' '}
          {skin?.skin.offset ?? SKIN_DEFAULTS.offset} px; the picture hangs{' '}
          {skin?.skin.ascent ?? SKIN_DEFAULTS.ascent} px above the title&apos;s baseline; then the
          prefix moves back
          {skin?.skin.advance == null ? ' (once the picture is measured)' : ''}, so the title&apos;s
          words are drawn over the art.
        </Hint>
      </div>
    </>
  )
}

function GlyphPreview({ pack, entryKey }: { pack: string; entryKey: string }) {
  const compiled = useCompiledResourcePacks()
  const glyphs = useGlyphMap()
  const glyph = compiled.resourcePacks[pack]?.glyphs[entryKey]
  if (!glyph) return null
  const tag = `<glyph:${pack}/${entryKey}>`
  return (
    <Row label={`In text: ${tag}`}>
      <span aria-label="Glyph preview">
        <TextPreview dark>
          <MiniText
            text={`<gray>Costs <yellow>25 ${tag}</yellow> each`}
            glyphs={glyphs}
            scale={2}
            base={{ color: '#ffffff' }}
          />
        </TextPreview>
      </span>
    </Row>
  )
}

function TooltipPreview({ pack, def }: { pack: string; def: TooltipDef }) {
  const url = useProjectFileUrl()
  const slice = (texture: string | undefined) =>
    texture
      ? `url("${url(resourcePackTexturePath(pack, texture))}") 9 fill / 18px stretch`
      : undefined
  return (
    <Row label="Preview">
      <span className={styles.tooltipPreview} aria-label="Tooltip preview">
        <span className={styles.tooltipLayer} style={{ borderImage: slice(def.background) }} />
        <span className={styles.tooltipLayer} style={{ borderImage: slice(def.frame) }} />
        <span className={styles.tooltipText}>
          <MiniText text="<aqua>Ruby" scale={2} />
          <MiniText text="<gray>50 coins" scale={2} />
        </span>
      </span>
    </Row>
  )
}
