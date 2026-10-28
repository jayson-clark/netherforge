/** The inspectors for a project texture, a game texture the pack redraws, and a game model it replaces. */
import { type ResourcePackFile } from '@/core/format'
import { resourcePackTexturePath } from '@/core/paths'
import { useApp } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { Row, Section, SelectField } from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { Hint, Muted } from '@/ui/text'
import { fieldPath } from '@/editors/shared/focus'
import {
  ENTRY_KINDS,
  entryTextures,
  setGameModel,
  setGameTexture,
  type EntryDef,
  packOf,
  type EntryKind,
} from './ops'
import { type GameTextures } from '@/minecraft/client/useGameTextures'
import styles from './ResourcePackEditor.module.css'
import { KIND_LABELS } from './shared'

export function TextureInspector({
  path,
  texture,
  url,
  model,
  onUse,
  onRedraw,
  onDeleted,
}: {
  path: string
  texture: string
  url: string
  model: ResourcePackFile
  onUse: (kind: EntryKind) => void
  onRedraw: () => void
  onDeleted: () => void
}) {
  const { workspace } = useApp()
  const pack = packOf(path)
  const users = ENTRY_KINDS.flatMap((kind) =>
    Object.entries(model[kind] ?? {})
      .filter(([, def]) => entryTextures(kind, def as EntryDef).includes(texture))
      .map(([key]) => `${KIND_LABELS[kind].one} ${key}`),
  ).concat(
    Object.entries(model.textures ?? {})
      .filter(([, it]) => it === texture)
      .map(([name]) => `game texture ${name}`),
  )
  return (
    <Section title={texture}>
      <div className={cx(styles.texturePreview, styles.checker)}>
        <img src={url} alt={texture} />
      </div>
      <Row label="Used by">
        <span>{users.length ? users.join(', ') : 'nothing yet'}</span>
      </Row>
      <Row label="Use as">
        <div className={styles.uses}>
          {ENTRY_KINDS.map((kind) => (
            <Button key={kind} size="small" onClick={() => onUse(kind)}>
              {KIND_LABELS[kind].one}
            </Button>
          ))}
          <Button size="small" onClick={onRedraw}>
            game texture
          </Button>
        </div>
      </Row>
      <Row label="">
        <Button
          size="small"
          icon="trash"
          onClick={async () => {
            const ok = await ask.confirm({
              title: 'Delete texture',
              message: `Delete resource_packs/${pack}/textures/${texture}?${users.length ? ` ${users.join(', ')} will point at nothing.` : ''}`,
              confirmLabel: 'Delete',
              danger: true,
            })
            if (ok !== true) return
            await workspace.getState().deletePath(resourcePackTexturePath(pack, texture))
            onDeleted()
          }}
        >
          Delete file
        </Button>
      </Row>
    </Section>
  )
}

export function GameTextureInspector({
  name,
  texture,
  textures,
  textureUrl,
  game,
  edit,
  onRemoved,
}: {
  name: string
  texture: string
  textures: string[]
  textureUrl: (texture: string) => string
  game: GameTextures | null
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onRemoved: () => void
}) {
  const size = game?.sizes[name]
  return (
    <Section
      title={`Game texture ${name}`}
      actions={
        <IconButton
          icon="trash"
          label="Draw the game's own again"
          onClick={() => {
            edit((draft) => setGameTexture(draft, name, undefined))
            onRemoved()
          }}
        />
      }
    >
      <div className={styles.gameTexture}>
        <figure>
          <div className={cx(styles.texturePreview, styles.checker)}>
            {game && size ? (
              <img src={game.url(name)} alt={`The game's ${name}`} />
            ) : (
              <Muted>?</Muted>
            )}
          </div>
          <figcaption>The game’s{size ? ` (${size[0]}×${size[1]})` : ''}</figcaption>
        </figure>
        <figure>
          <div className={cx(styles.texturePreview, styles.checker)}>
            {textures.includes(texture) ? (
              <img src={textureUrl(texture)} alt={`The pack's ${texture}`} />
            ) : (
              <Icon name="warning" />
            )}
          </div>
          <figcaption>The pack’s</figcaption>
        </figure>
      </div>
      <SelectField
        label="Drawn with"
        dataPath={`textures.${name}`}
        value={texture}
        options={[
          ...textures.map((it) => ({ value: it, label: it })),
          ...(textures.includes(texture)
            ? []
            : [{ value: texture, label: `${texture} (missing)` }]),
        ]}
        onChange={(next) => edit((draft) => setGameTexture(draft, name, next))}
      />
      <Hint>
        Every player sees it in place of the game&apos;s. Draw it at any size of the same shape
        (32×32 for a 16×16 block is twice the detail); a screen&apos;s sheet (<code>gui/…</code>)
        and a colour map take exactly the game&apos;s size. An animation needs its{' '}
        <code>.mcmeta</code> beside the picture.
      </Hint>
    </Section>
  )
}

export function GameModelInspector({
  name,
  file,
  modelFiles,
  edit,
  onRemoved,
}: {
  name: string
  file: string
  modelFiles: string[]
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onRemoved: () => void
}) {
  return (
    <Section
      title={`Game model ${name}`}
      actions={
        <IconButton
          icon="trash"
          label="Draw the game's own again"
          onClick={() => {
            edit((draft) => setGameModel(draft, name, undefined))
            onRemoved()
          }}
        />
      }
    >
      <SelectField
        label="Drawn with"
        dataPath={fieldPath(['models', name])}
        value={file}
        options={[
          ...modelFiles.map((it) => ({ value: it, label: `models/${it}` })),
          ...(modelFiles.includes(file)
            ? []
            : [{ value: file, label: `models/${file} (missing)` }]),
        ]}
        onChange={(next) => edit((draft) => setGameModel(draft, name, next))}
      />
      <Hint>
        Every block state and item the game draws with {name} takes this shape, for every player.
        What collides with a block and what a player selects stay the game&apos;s.
      </Hint>
    </Section>
  )
}
