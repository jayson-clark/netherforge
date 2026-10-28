/**
 * The pack editor: a gallery of the pack's skins, glyphs, item models,
 * tooltips and textures, and in the inspector the picked one, with a preview
 * drawn from format's `compileResourcePacks` numbers (a skin over the window it
 * fits, a glyph inside a line of text, an item model in a slot, a tooltip's
 * nine-slice frame). PNGs are imported into `textures/` and Ogg files into
 * `sounds/` as project files; every sound file is a sound event, and pack.json
 * adds events that pick among files or replace the game's. Game textures and
 * game models are the game's own (`block/stone`) it redraws, for every
 * player, each texture started from a copy of the game's out of the client
 * import. The HUD's bars, rows of icons and lines of text are gallery entries
 * too; which of the game's HUD elements it hides and which sprites it redraws
 * are the pack's own fields.
 */
import { setKey } from '@/core/draft'
import { useRef, useState, type ChangeEvent } from 'react'
import {
  RESOURCE_PACK_SOUND_EXTENSION,
  RESOURCE_PACK_SOUND_FILE_PATTERN,
  RESOURCE_PACK_SOUND_KEY_RULE,
  RESOURCE_PACK_TEXTURE_PATH_PATTERN,
  RESOURCE_PACK_TEXTURE_PATH_RULE,
  type ResourcePackFile,
} from '@/core/format'
import { ID_PATTERN, resourcePackSoundPath, resourcePackTexturePath } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import { ask } from '@/ui/dialogs'
import { Section, TextField } from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { Icon } from '@/ui/Icon'
import { Hint, Muted } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import { Gallery, GallerySection } from '@/editors/shared/Gallery'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import {
  addEntry,
  addHudElement,
  addSoundEntry,
  DEFAULT_FOLDER,
  ENTRY_KINDS,
  entryTextures,
  freeEntryKey,
  gameTextureCopyPath,
  HUD_KINDS,
  hudKeys,
  hudKindOf,
  newHudElement,
  setGameModel,
  type HudKind,
  importPath,
  isOgg,
  newEntry,
  pngSize,
  setGameTexture,
  soundEvents,
  soundFilesOf,
  soundImportPath,
  soundKeyOf,
  texturesOf,
  unusedTextures,
  type EntryDef,
  packOf,
  type EntryKind,
} from './ops'
import { filesIn, splitPackagePath } from '@/core/store/packages'
import { useResourcePackModels, useProjectFileUrl } from '@/state/useResourcePacks'
import { useGameTextures } from '@/minecraft/client/useGameTextures'
import styles from './ResourcePackEditor.module.css'
import { EntryInspector } from './EntryInspector'
import { GameHudSection, HudInspector } from './HudInspector'
import { SoundInspector } from './SoundInspector'
import { GameModelInspector, GameTextureInspector, TextureInspector } from './TextureInspectors'
import { HUD_LABELS, KIND_LABELS, type Pick } from './shared'

const capitalized = (text: string) => text[0]!.toUpperCase() + text.slice(1)

export function ResourcePackEditor({ path }: { path: string }) {
  const { workspace } = useApp()
  const { doc, model, edit } = useModelDoc<ResourcePackFile>(path)
  // A package's pack (opened read-only from Dependencies) has its pictures and sounds in the package.
  const files = useWorkspace((s) => filesIn(s, splitPackagePath(path)[0]))
  const picked = usePrimary('resource_pack', path)
  const view = useView('resource_pack', path)
  const pack = packOf(path)
  const textures = texturesOf(files, pack)
  const soundFiles = soundFilesOf(files, pack)
  const url = useProjectFileUrl()
  const input = useRef<HTMLInputElement>(null)
  const soundInput = useRef<HTMLInputElement>(null)
  const [importFolder, setImportFolder] = useState(DEFAULT_FOLDER.skins)
  const game = useGameTextures()
  const packModels = useResourcePackModels()
  const ws = () => workspace.getState()
  const pick = (next: Pick) => (next ? view.select(next) : view.select())

  useFocusRequests(path, (segments) => {
    const [kind, key] = segments
    // `textures` in pack.json is the game's textures it redraws, not the pack's files.
    if (kind === 'textures' && typeof key === 'string') {
      pick({ kind: 'game', key })
      return segments.slice(2)
    }
    if (kind === 'models' && typeof key === 'string') {
      pick({ kind: 'gameModel', key })
      return segments
    }
    const [, part, element] = segments
    if (kind === 'hud' && HUD_KINDS.includes(part as HudKind) && typeof element === 'string') {
      pick({ kind: 'hud', key: element })
      return segments
    }
    if ((ENTRY_KINDS.includes(kind as EntryKind) || kind === 'sounds') && typeof key === 'string') {
      pick({ kind: kind as EntryKind | 'sounds', key })
      return segments.slice(2)
    }
    return segments
  })

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const textureUrl = (texture: string) => url(resourcePackTexturePath(pack, texture))

  const importFiles = async (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = [...(event.target.files ?? [])]
    event.target.value = ''
    let last: string | null = null
    for (const file of chosen) {
      const bytes = new Uint8Array(await file.arrayBuffer())
      if (!pngSize(bytes)) {
        ws().notify('error', `${file.name} isn't a PNG`)
        continue
      }
      const target = await ask.prompt({
        title: `Import ${file.name}`,
        label: `Save as (under resource_packs/${pack}/textures/)`,
        initial: importPath(file.name, importFolder),
        confirmLabel: 'Import',
        validate: (value) =>
          RESOURCE_PACK_TEXTURE_PATH_PATTERN.test(value)
            ? null
            : capitalized(RESOURCE_PACK_TEXTURE_PATH_RULE),
      })
      if (!target) continue
      if (textures.includes(target)) {
        const ok = await ask.confirm({
          title: 'Replace texture',
          message: `${target} exists. Replace it?`,
          confirmLabel: 'Replace',
          danger: true,
        })
        if (ok !== true) continue
      }
      if (await ws().writeBinary(resourcePackTexturePath(pack, target), bytes)) last = target
    }
    if (last) pick({ kind: 'textures', key: last })
  }

  const importSounds = async (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = [...(event.target.files ?? [])]
    event.target.value = ''
    let last: string | null = null
    for (const file of chosen) {
      const bytes = new Uint8Array(await file.arrayBuffer())
      if (!isOgg(bytes)) {
        ws().notify('error', `${file.name} isn't an Ogg file: Minecraft only plays Ogg Vorbis`)
        continue
      }
      const target = await ask.prompt({
        title: `Import ${file.name}`,
        label: `Save as (under resource_packs/${pack}/sounds/)`,
        message: `It plays as ${pack}/<path without ${RESOURCE_PACK_SOUND_EXTENSION}>.`,
        initial: soundImportPath(file.name, ''),
        confirmLabel: 'Import',
        validate: (value) =>
          RESOURCE_PACK_SOUND_FILE_PATTERN.test(value)
            ? null
            : `A ${RESOURCE_PACK_SOUND_EXTENSION} path: ${RESOURCE_PACK_SOUND_KEY_RULE}`,
      })
      if (!target) continue
      if (soundFiles.includes(target)) {
        const ok = await ask.confirm({
          title: 'Replace sound',
          message: `${target} exists. Replace it?`,
          confirmLabel: 'Replace',
          danger: true,
        })
        if (ok !== true) continue
      }
      if (await ws().writeBinary(resourcePackSoundPath(pack, target), bytes)) last = target
    }
    if (last) pick({ kind: 'sounds', key: soundKeyOf(last)! })
  }

  const sounds = soundEvents(model, soundFiles)

  const add = async (kind: EntryKind, texture?: string) => {
    const from =
      texture ?? unusedTextures(model, textures, packModels[pack]?.files)[0] ?? textures[0]
    if (!from) {
      ws().notify('error', 'Import a PNG first: every entry is drawn from a texture.')
      return
    }
    const key = await ask.prompt({
      title: `New ${KIND_LABELS[kind].one}`,
      label: 'Key',
      message: `Others refer to it as ${pack}/<key>.`,
      initial: freeEntryKey(model, kind, from.split('/').pop() ?? from),
      confirmLabel: 'Create',
      validate: (value) =>
        !ID_PATTERN.test(value)
          ? 'Lowercase letters, digits and _'
          : model[kind]?.[value]
            ? 'That key is taken'
            : null,
    })
    if (!key) return
    edit((draft) => addEntry(draft, kind, key, newEntry(kind, from)))
    pick({ kind, key })
  }

  /**
   * Redraws one of the game's textures: with [texture] when given, else with a copy of the game's own picture (and
   * its animation) put in the pack to draw over.
   */
  const redraw = async (texture?: string) => {
    if (!game || Object.keys(game.sizes).length === 0) {
      ws().notify(
        'error',
        'Import Minecraft (again, if it was imported before the editor listed its textures) to pick a game texture.',
      )
      return
    }
    const names = Object.keys(game.sizes)
    const name = await ask.prompt({
      title: 'Redraw a game texture',
      label: 'Game texture (its path under textures/, without .png)',
      message:
        'Every player sees the pack’s picture instead: block/stone, item/diamond, entity/zombie/zombie…',
      confirmLabel: texture ? 'Redraw' : 'Copy and redraw',
      suggestions: names,
      validate: (value) => (value in game.sizes ? null : 'The game has no texture by that name'),
    })
    if (!name) return
    let picture = texture ?? model.textures?.[name]
    if (!picture) {
      picture = gameTextureCopyPath(name)
      if (!textures.includes(picture)) {
        const copied = await game.files(name)
        if (!copied) {
          ws().notify('error', `Couldn't read the game's ${name}`)
          return
        }
        const target = resourcePackTexturePath(pack, picture)
        if (!(await ws().writeBinary(target, copied.png))) return
        if (copied.mcmeta) await ws().writeBinary(`${target}.mcmeta`, copied.mcmeta)
      }
    }
    const chosen = picture
    edit((draft) => setGameTexture(draft, name, chosen))
    pick({ kind: 'game', key: name })
  }
  const redrawn = Object.entries(model.textures ?? {}).sort(([a], [b]) =>
    a < b ? -1 : a > b ? 1 : 0,
  )
  const remodelled = Object.entries(model.models ?? {}).sort(([a], [b]) =>
    a < b ? -1 : a > b ? 1 : 0,
  )
  const modelFiles = Object.keys(packModels[pack]?.files ?? {}).sort()

  /** Redraws one of the game's models with a model file of the pack. */
  const remodel = async () => {
    if (modelFiles.length === 0) {
      ws().notify(
        'error',
        'Put a model file in the pack’s models/ folder first: it’s what’s drawn.',
      )
      return
    }
    const name = await ask.prompt({
      title: 'Redraw a game model',
      label: 'Game model (its path under models/, without .json)',
      message: 'Every block state and item the game draws with it takes the new shape.',
      initial: 'block/',
      confirmLabel: 'Redraw',
      validate: (value) =>
        !/^[a-z0-9_]+(\/[a-z0-9_.-]+)+$/.test(value)
          ? 'A model path: block/lantern, item/stick…'
          : model.models?.[value]
            ? 'The pack redraws it already'
            : null,
    })
    if (!name) return
    edit((draft) => setGameModel(draft, name, modelFiles[0]))
    pick({ kind: 'gameModel', key: name })
  }

  const hud = hudKeys(model)
  const addHud = async (kind: HudKind) => {
    const texture = textures[0]
    if (!texture && kind !== 'texts') {
      ws().notify('error', 'Import a PNG first: a bar or a row of icons is drawn from textures.')
      return
    }
    const key = await ask.prompt({
      title: `New HUD ${HUD_LABELS[kind]}`,
      label: 'Key',
      message: `Scripts fill it as ${pack}/<key> (player:hud()).`,
      initial: '',
      confirmLabel: 'Create',
      validate: (value) =>
        !ID_PATTERN.test(value)
          ? 'Lowercase letters, digits and _'
          : hudKindOf(model, value)
            ? 'Another HUD element has that key'
            : null,
    })
    if (!key) return
    edit((draft) => addHudElement(draft, kind, key, newHudElement(kind, texture ?? '')))
    pick({ kind: 'hud', key })
  }

  const addSound = async () => {
    const key = await ask.prompt({
      title: 'New sound event',
      label: 'Key',
      message: `${pack}/<key> plays one of its files at random. A key like minecraft:block.stone.break replaces the game's event for every player.`,
      initial: '',
      confirmLabel: 'Create',
      validate: (value) =>
        !/^([a-z0-9_]+(\/[a-z0-9_]+)*|minecraft:[a-z0-9_.]+)$/.test(value)
          ? 'Lowercase letters, digits, _ and / (or minecraft:<event>)'
          : sounds.some((it) => it.key === value)
            ? 'That event exists'
            : null,
    })
    if (!key) return
    edit((draft) => addSoundEntry(draft, key, soundFiles.slice(0, 1)))
    pick({ kind: 'sounds', key })
  }

  return (
    <EditorScreen>
      <EditorBar
        title={
          <>
            {model.name ?? pack} <Muted>({pack}/…)</Muted>
          </>
        }
      >
        <label className={styles.into}>
          Into
          <select
            aria-label="Import into folder"
            value={importFolder}
            onChange={(event) => setImportFolder(event.target.value)}
          >
            {[...new Set([...Object.values(DEFAULT_FOLDER), ''])].map((it) => (
              <option key={it} value={it}>
                textures/{it}
              </option>
            ))}
          </select>
        </label>
        <Button size="small" icon="plus" onClick={() => input.current?.click()}>
          Import PNG…
        </Button>
        <input
          ref={input}
          type="file"
          accept="image/png"
          multiple
          hidden
          aria-label="PNG files to import"
          onChange={(event) => void importFiles(event)}
        />
        <Button size="small" icon="plus" onClick={() => soundInput.current?.click()}>
          Import OGG…
        </Button>
        <input
          ref={soundInput}
          type="file"
          accept={`${RESOURCE_PACK_SOUND_EXTENSION},audio/ogg`}
          multiple
          hidden
          aria-label="Ogg files to import"
          onChange={(event) => void importSounds(event)}
        />
        <EditAsJson path={path} />
      </EditorBar>
      <Gallery>
        {ENTRY_KINDS.map((kind) => {
          const entries = Object.entries(model[kind] ?? {}) as [string, EntryDef][]
          return (
            <GallerySection
              key={kind}
              title={KIND_LABELS[kind].title}
              picked={picked?.kind === kind ? picked.key : null}
              onPick={(key) => pick({ kind, key })}
              actions={
                <IconButton
                  icon="plus"
                  label={`New ${KIND_LABELS[kind].one}`}
                  onClick={() => void add(kind)}
                />
              }
              cards={entries.map(([key, def]) => {
                const texture = entryTextures(kind, def)[0]
                return {
                  key,
                  label: `${KIND_LABELS[kind].one} ${key}`,
                  caption: key,
                  picture:
                    texture && textures.includes(texture) ? (
                      <img src={textureUrl(texture)} alt="" />
                    ) : (
                      <Icon name="warning" />
                    ),
                }
              })}
            />
          )
        })}
        <GallerySection
          title="Game textures"
          picked={picked?.kind === 'game' ? picked.key : null}
          onPick={(key) => pick({ kind: 'game', key })}
          actions={
            <IconButton icon="plus" label="Redraw a game texture" onClick={() => void redraw()} />
          }
          cards={redrawn.map(([name, texture]) => ({
            key: name,
            label: `Game texture ${name}`,
            title: `${name} → textures/${texture}`,
            caption: name,
            picture: textures.includes(texture) ? (
              <img src={textureUrl(texture)} alt="" />
            ) : (
              <Icon name="warning" />
            ),
          }))}
        />
        <GallerySection
          title="Game models"
          picked={picked?.kind === 'gameModel' ? picked.key : null}
          onPick={(key) => pick({ kind: 'gameModel', key })}
          actions={
            <IconButton icon="plus" label="Redraw a game model" onClick={() => void remodel()} />
          }
          cards={remodelled.map(([name, file]) => ({
            key: name,
            label: `Game model ${name}`,
            title: `${name} → models/${file}`,
            caption: name,
            picture: <Icon name={modelFiles.includes(file) ? 'cube' : 'warning'} />,
          }))}
        />
        <GallerySection
          title="HUD"
          picked={picked?.kind === 'hud' ? picked.key : null}
          onPick={(key) => pick({ kind: 'hud', key })}
          actions={
            <>
              {HUD_KINDS.map((kind) => (
                <IconButton
                  key={kind}
                  icon={kind === 'bars' ? 'chart' : kind === 'icons' ? 'grid' : 'list'}
                  label={`New HUD ${HUD_LABELS[kind]}`}
                  onClick={() => void addHud(kind)}
                />
              ))}
            </>
          }
          cards={hud.map((key) => {
            const texture = hudPicture(model, key)
            return {
              key,
              label: `HUD ${HUD_LABELS[hudKindOf(model, key)!]} ${key}`,
              caption: key,
              picture:
                texture && textures.includes(texture) ? (
                  <img src={textureUrl(texture)} alt="" />
                ) : (
                  <Icon name={texture ? 'warning' : 'list'} />
                ),
            }
          })}
        />
        <GallerySection
          title="Textures"
          picked={picked?.kind === 'textures' ? picked.key : null}
          onPick={(key) => pick({ kind: 'textures', key })}
          cards={textures.map((texture) => ({
            key: texture,
            label: `Texture ${texture}`,
            title: texture,
            caption: (
              <>
                {texture}
                {unusedTextures(model, [texture], packModels[pack]?.files).length > 0 && (
                  <Muted> (unused)</Muted>
                )}
              </>
            ),
            picture: <img src={textureUrl(texture)} alt="" />,
          }))}
        />
        <GallerySection
          title="Sounds"
          picked={picked?.kind === 'sounds' ? picked.key : null}
          onPick={(key) => pick({ kind: 'sounds', key })}
          actions={
            <IconButton icon="plus" label="New sound event" onClick={() => void addSound()} />
          }
          cards={sounds.map(({ key, files: plays }) => ({
            key,
            label: `Sound ${key}`,
            title: `${pack}/${key}`,
            caption: key,
            picture: (
              <Icon name={plays.some((it) => !soundFiles.includes(it)) ? 'warning' : 'sound'} />
            ),
          }))}
        />
      </Gallery>
      <InspectorPanel>
        {picked?.kind === 'textures' && textures.includes(picked.key) && (
          <TextureInspector
            path={path}
            texture={picked.key}
            url={textureUrl(picked.key)}
            model={model}
            onUse={(kind) => void add(kind, picked.key)}
            onRedraw={() => void redraw(picked.key)}
            onDeleted={() => pick(null)}
          />
        )}
        {picked?.kind === 'game' && model.textures?.[picked.key] !== undefined && (
          <GameTextureInspector
            name={picked.key}
            texture={model.textures[picked.key]!}
            textures={textures}
            textureUrl={textureUrl}
            game={game}
            edit={edit}
            onRemoved={() => pick(null)}
          />
        )}
        {picked?.kind === 'gameModel' && model.models?.[picked.key] !== undefined && (
          <GameModelInspector
            name={picked.key}
            file={model.models[picked.key]!}
            modelFiles={modelFiles}
            edit={edit}
            onRemoved={() => pick(null)}
          />
        )}
        {picked?.kind === 'hud' && hudKindOf(model, picked.key) && (
          <HudInspector
            pack={pack}
            hudKey={picked.key}
            model={model}
            textures={textures}
            edit={edit}
            onPick={pick}
          />
        )}
        {picked?.kind === 'sounds' && sounds.some((it) => it.key === picked.key) && (
          <SoundInspector
            pack={pack}
            soundKey={picked.key}
            model={model}
            plays={sounds.find((it) => it.key === picked.key)!.files}
            soundFiles={soundFiles}
            edit={edit}
            onDeleted={() => pick(null)}
          />
        )}
        {picked &&
          picked.kind !== 'textures' &&
          picked.kind !== 'sounds' &&
          picked.kind !== 'game' &&
          picked.kind !== 'gameModel' &&
          picked.kind !== 'hud' &&
          model[picked.kind]?.[picked.key] && (
            <EntryInspector
              pack={pack}
              kind={picked.kind}
              entryKey={picked.key}
              def={model[picked.kind]![picked.key]!}
              model={model}
              textures={textures}
              edit={edit}
              onPick={pick}
            />
          )}
        <Section title="Resource pack" icon="image">
          <TextField
            label="Name"
            dataPath="name"
            value={model.name}
            placeholder="(for people)"
            onChange={(value) => edit((draft) => setKey(draft, 'name', value))}
          />
          <TextField
            label="Description"
            dataPath="description"
            value={model.description}
            placeholder="Shown in the client's pack list"
            onChange={(value) => edit((draft) => setKey(draft, 'description', value))}
          />
          <Hint>
            Menus, items and text refer to what&apos;s in it as <code>{pack}/&lt;key&gt;</code>, the
            pack&apos;s folder name and the key. Every <code>.ogg</code> under <code>sounds/</code>{' '}
            is the sound <code>{pack}/&lt;its path&gt;</code>.
          </Hint>
        </Section>
        <GameHudSection model={model} textures={textures} game={game} edit={edit} />
      </InspectorPanel>
    </EditorScreen>
  )
}

/** The picture a HUD element's card shows: a bar's full segment, a row's first icon; none for text. */
function hudPicture(model: ResourcePackFile, key: string): string | undefined {
  const hud = model.hud
  return hud?.bars?.[key]?.full ?? Object.values(hud?.icons?.[key]?.icons ?? {})[0]
}
