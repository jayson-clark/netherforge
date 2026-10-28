/**
 * A map's screen (`maps/<id>/`): the map in 3D around its spawn
 * (`MapView`, from the region files of the dimension copies are made
 * from), what its `level.dat` and saved data say (name, seed, spawn, game
 * rules, data version), its dimensions and its size on disk, and "Save a
 * dev-server world as this map". A map is never played on:
 * scripts copy it into new worlds (`nf.worlds.copy`), so nothing here edits
 * it.
 *
 * Capturing: the plugin saves and flushes the world (`save_world`) and says
 * where its files are in the dev server's folder; the backend copies them in
 * (`captureMap`, off the UI thread, path-scoped at both ends); then
 * the editor rewrites the copy's `level.dat` with the world's own spawn
 * (a world that isn't the main one shares the main world's) through the
 * workspace, which hot-reloads it.
 */
import { useEffect, useState } from 'react'
import type { CaptureProgress } from '@/core/backend/types'
import { readNbt, writeNbt, type NbtRoot } from '@netherforge/structures/nbt'
import { basename, mainFileOf, resourceOf } from '@/core/paths'
import { useApp, useRun, useWorkspace } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { Row, Section } from '@/ui/fields'
import { Button } from '@/ui/Button'
import { Hint, Tone } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, InspectorNote } from '@/editors/shared/EditorLayout'
import { ViewportFallback } from '@/editors/shared/viewport/parts'
import { Actions, CountsTable } from '@/editors/structure/parts'
import styles from './MapEditor.module.css'
import { readProjectBytes } from '@/core/backend/projectBytes'
import { useLoadedWorlds, WorldSelect } from '@/editors/shared/ServerWorlds'
import { useAsyncResult } from '@/ui/useAsync'
import {
  dimensionsOf,
  GAME_RULES_FILE,
  readWorldInfo,
  MAP_DIMENSION,
  WORLD_GEN_FILE,
  withSpawn,
  type WorldInfo,
} from '@/minecraft/world/level'
import { MapView } from './MapView'

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error))

/** Bytes as a person reads them. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`
}

/** [path] is the map's folder (`maps/arena`). */
export function MapEditor({ path: folder }: { path: string }) {
  const { backend } = useApp()
  const id = basename(folder)
  // The group folders it's in (`(arenas)`), which a capture writes into too.
  const group = resourceOf(folder)?.group ?? ''
  const level = mainFileOf('map', id, group)
  const allFiles = useWorkspace((s) => s.files)
  const stamps = useWorkspace((s) => s.fileStamps)
  const inside = allFiles.filter((it) => it.startsWith(`${folder}/`))
  const relative = inside.map((it) => it.slice(folder.length + 1))
  const hasLevel = inside.includes(level)
  // Which dimension's saved data to show: the one copies are made from, else the only one there is.
  const dimensions = dimensionsOf(relative)
  const worldDir = relative.some((it) => it.startsWith(`${MAP_DIMENSION}/`))
    ? MAP_DIMENSION
    : dimensions.length === 1 && dimensions[0]!.includes(':')
      ? `dimensions/${dimensions[0]!.replace(':', '/')}`
      : null
  const worldGen = worldDir ? `${folder}/${worldDir}/${WORLD_GEN_FILE}` : null
  const gameRules = worldDir ? `${folder}/${worldDir}/${GAME_RULES_FILE}` : null
  const present = (path: string | null) => (path && inside.includes(path) ? path : null)
  const reads = [level, present(worldGen), present(gameRules)]
  const key = reads.map((it) => (it ? `${it}@${stamps[it] ?? 0}` : '-')).join('|')

  const info = useAsyncResult(`${hasLevel}|${key}`, () => {
    if (!hasLevel) return null
    const read = async (path: string | null): Promise<NbtRoot | undefined> =>
      path ? readNbt(await readProjectBytes(backend, path, stamps[path])) : undefined
    return Promise.all(reads.map(read)).then(([levelRoot, gen, rules]) =>
      readWorldInfo(levelRoot!, gen, rules),
    )
  })

  // Size on disk: the backend's listing has every file's size.
  const size = useAsyncResult(`${folder}|${inside.length}|${key}`, () =>
    backend
      .listFiles()
      .then((entries) =>
        entries
          .filter((it) => it.path.startsWith(`${folder}/`))
          .reduce((sum, it) => sum + it.size, 0),
      ),
  )

  // The region files of the world copies are made from (or an older save's own).
  const regionDir = worldDir
    ? `${folder}/${worldDir}/region`
    : relative.some((it) => it.startsWith('region/'))
      ? `${folder}/region`
      : null
  const regionFiles: Record<string, number> = {}
  for (const path of inside) {
    const name =
      regionDir && path.startsWith(`${regionDir}/`) ? path.slice(regionDir.length + 1) : null
    if (name && !name.includes('/')) regionFiles[name] = stamps[path] ?? 0
  }
  const hasRegions = Object.keys(regionFiles).some((name) => name.endsWith('.mca'))

  return (
    <EditorScreen aria-label={`Map ${id}`}>
      {regionDir && hasRegions && info?.value ? (
        <MapView
          path={folder}
          crumb={`${folder}/`}
          regionDir={regionDir}
          files={regionFiles}
          spawn={info.value.spawn}
        />
      ) : (
        <>
          <EditorBar title={`${folder}/`} />
          <ViewportFallback>
            <span role="note" aria-label="Map preview">
              {!hasLevel
                ? 'Save a dev-server world here to see it.'
                : info?.error
                  ? "The map isn't drawn without a readable level.dat."
                  : !info?.value
                    ? 'Reading…'
                    : `${regionDir ?? `${folder}/${MAP_DIMENSION}/region`}/ has no region files, so there's nothing to draw.`}
            </span>
          </ViewportFallback>
        </>
      )}
      <InspectorPanel label="Map">
        {!hasLevel ? (
          <InspectorNote>
            <span role="status">
              {inside.length === 0
                ? `There's no ${folder}/ yet. Save a dev-server world into it below.`
                : `${folder}/ has no level.dat, so it isn't a world yet.`}
            </span>
          </InspectorNote>
        ) : info?.error ? (
          <InspectorNote>
            <Tone tone="error" role="status">
              {level} can't be read: {info.error}
            </Tone>
          </InspectorNote>
        ) : info?.value ? (
          <MapInfo
            id={id}
            info={info.value}
            dimensions={dimensions}
            size={size?.value ?? null}
            files={inside.length}
          />
        ) : (
          <InspectorNote>Reading…</InspectorNote>
        )}
        <CaptureWorld id={id} folder={folder} exists={inside.length > 0} />
      </InspectorPanel>
    </EditorScreen>
  )
}

function MapInfo({
  id,
  info,
  dimensions,
  size,
  files,
}: {
  id: string
  info: WorldInfo
  dimensions: string[]
  size: number | null
  files: number
}) {
  return (
    <>
      <Section title="World" icon="globe">
        <Row label="Name">
          <span aria-label="World name">{info.name ?? 'not given'}</span>
        </Row>
        <Row label="Seed">
          <span aria-label="Seed">{info.seed ?? 'not given'}</span>
        </Row>
        <Row label="Spawn">
          <span aria-label="Spawn">{info.spawn ? info.spawn.join(', ') : 'not given'}</span>
        </Row>
        <Row label="Data version">
          <span aria-label="Data version">
            {info.dataVersion ?? 'not given'}
            {info.version ? ` (Minecraft ${info.version})` : ''}
          </span>
        </Row>
        <Row label="Dimensions">
          <span aria-label="Dimensions">
            {dimensions.length > 0 ? dimensions.join(', ') : 'none'}
          </span>
        </Row>
        <Row label="On disk">
          <span aria-label="Size on disk">
            {size === null ? '…' : `${formatBytes(size)} in ${files} files`}
          </span>
        </Row>
        <Hint>
          Scripts copy it into new worlds: <code>nf.worlds.copy(&quot;{id}&quot;, name)</code>.
          Copies are made from its {MAP_DIMENSION.split('/').pop()}.
        </Hint>
      </Section>
      <Section title={`Game rules (${info.gameRules.length})`}>
        {info.gameRules.length === 0 ? (
          <Hint>None saved.</Hint>
        ) : (
          <CountsTable
            label="Game rules"
            rows={info.gameRules.map(({ rule, value }) => [rule, value])}
          />
        )}
      </Section>
    </>
  )
}

function CaptureWorld({ id, folder, exists }: { id: string; folder: string; exists: boolean }) {
  const { backend, workspace } = useApp()
  const connected = useRun((s) => s.server.bridgeConnected)
  const { worlds, refresh } = useLoadedWorlds(connected)
  const [world, setWorld] = useState('')
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<CaptureProgress | null>(null)
  const [message, setMessage] = useState<{ kind: 'error' | 'info'; text: string } | null>(null)
  const chosen = worlds.some((it) => it.name === world)
    ? world
    : (worlds.find((it) => it.main)?.name ?? worlds[0]?.name ?? '')

  useEffect(() => {
    if (!busy) return
    let unlisten: (() => void) | null = null
    let live = true
    void backend
      .onCaptureProgress((event) => setProgress(event))
      .then((stop) => {
        if (live) unlisten = stop
        else stop()
      })
    return () => {
      live = false
      unlisten?.()
    }
  }, [backend, busy])

  const capture = async () => {
    setMessage(null)
    if (exists) {
      const ok = await ask.confirm({
        title: `Replace ${id}`,
        message: `Replace everything in ${folder}/ with the world ${chosen} as it is now? Git can bring the old one back if it was committed.`,
        confirmLabel: 'Replace',
        danger: true,
      })
      if (ok !== true) return
    }
    setBusy(true)
    setProgress(null)
    try {
      const saved = await backend.bridgeRequest('save_world', { world: chosen })
      // Into the group folders the map is in (`(arenas)/duel`), as the tab has it.
      const group = resourceOf(folder)?.group ?? ''
      await backend.captureMap(
        { level: saved.level, dimension: saved.dimension },
        group ? `${group}/${id}` : id,
        exists,
      )
      const ws = workspace.getState()
      await ws.refreshFiles()
      // The copy's spawn is the world's own, whichever world it was.
      const levelPath = mainFileOf('map', id, group)
      const level = await readNbt(await readProjectBytes(backend, levelPath, Date.now()))
      await ws.writeBinary(levelPath, await writeNbt(withSpawn(level, saved.spawn)))
      setMessage({ kind: 'info', text: `Saved ${chosen} into ${folder}/.` })
    } catch (error) {
      setMessage({ kind: 'error', text: `Couldn't save the world: ${errorText(error)}` })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Section title="Save a dev-server world as this map" icon="save">
      {!connected && <Hint>Start the dev server to save one of its worlds here.</Hint>}
      <WorldSelect
        worlds={worlds}
        value={chosen}
        onChange={setWorld}
        onRefresh={refresh}
        disabled={!connected || busy}
      />
      <Hint>
        The server saves the world first; its region files and saved data are copied, without its
        lock, identity and players&apos; files.
      </Hint>
      <Actions>
        <Button
          variant="primary"
          size="small"
          icon="save"
          disabled={!connected || busy || !chosen}
          onClick={() => void capture()}
        >
          {exists ? 'Save and replace' : 'Save as map'}
        </Button>
      </Actions>
      {busy && (
        <progress
          className={styles.progress}
          aria-label="Copying the world"
          max={progress?.total || 1}
          value={progress?.done ?? 0}
        />
      )}
      {message && (
        <Hint tone={message.kind === 'error' ? 'error' : undefined} role="status">
          {message.text}
        </Hint>
      )}
    </Section>
  )
}
