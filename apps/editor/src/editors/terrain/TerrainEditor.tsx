/**
 * A terrain (`terrain/<id>.json`, docs/format/terrain.md): a live preview of the world it makes (a map from
 * above and a slice through the ground, drawn by format's own generator, the server's code, for a seed) and, in the
 * inspector, every part of the file. The map fills the editor and pans and zooms freely: it's drawn in tiles by a few
 * workers (`tiles.ts`, `worker/`), kept as the view moves, so only what comes into view is new work. The slice has a
 * worker of its own and is drawn again only when its line changes. Both are drawn again whenever the file changes,
 * from the file as it is, with the project structures its decorations place read from their files and, for a file
 * with a `script`, the Lua it runs (`terrain/<id>.lua` and the modules, as the editor holds them: an open script's
 * unsaved text included), so a change to the script redraws it too.
 */
import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import { Group, Panel } from 'react-resizable-panels'
import {
  MANIFEST_FILE,
  TERRAIN_DEFAULTS,
  type DimensionTypeFile,
  type ProjectManifest,
  type TerrainFile,
  type TerrainPreviewResult,
  type TerrainScriptError,
  type TerrainStructureInput,
} from '@/core/format'
import { readProjectBytes } from '@/core/backend/projectBytes'
import { mainFileIn, resourceIdsOf } from '@/core/paths'
import { isModel } from '@/core/store/documents'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useHomeNamespace } from '@/state/useResourcePacks'
import { readNbt } from '@netherforge/structures/nbt'
import { parseStructure } from '@netherforge/structures/structure'
import { useApp, useWorkspace } from '@/state/providers'
import { useAsyncResult } from '@/ui/useAsync'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { useView, useViewState } from '@/editors/views'
import { Button } from '@/ui/Button'
import { NumberInput } from '@/ui/fields'
import { Splitter } from '@/ui/Splitter'
import { Empty } from '@/ui/text'
import {
  hasScript,
  scriptPaths,
  structureInput,
  structuresNamed,
} from '@netherforge/terrain-preview'
import { heightChoices, pickHeights, type HeightChoice } from './heights'
import { TerrainInspector } from './inspector/Inspector'
import { MapView, type SliceLine } from './MapView'
import { SliceView } from './SliceView'
import { TileScheduler, type MapCamera } from './tiles'
import {
  Lane,
  terrainPreviewWorker,
  tileLaneCount,
  useSlicePreview,
  type SliceAsk,
} from './worker/client'
import { fileKeyOf, type PreviewFile, type PreviewWorld } from './worker/core'
import styles from './TerrainEditor.module.css'

/** How fine the map is drawn: screen pixels a cell aims to be. */
const DETAILS = [
  { value: 2, label: 'Fine' },
  { value: 4, label: 'Normal' },
  { value: 8, label: 'Coarse' },
]
/** How wide the slice can be, in blocks. */
const WIDTHS = [128, 256, 512, 1024]

const idOf = (path: string) => path.replace(/^.*\//, '').replace(/\.json$/, '')

/** Reads [names]' `.nbt` files as the preview places them; one that can't be read is left out, as on a server. */
function useStructures(names: string[]): {
  structures: Record<string, TerrainStructureInput>
  key: string
} | null {
  const { backend } = useApp()
  const files = useWorkspace((s) => s.files)
  const stamps = useWorkspace((s) => s.fileStamps)
  const paths = names.map((name) => mainFileIn(files, 'structure', name))
  const key = paths
    .map((path) => `${path}@${files.includes(path) ? (stamps[path] ?? 0) : '-'}`)
    .join('|')
  const loaded = useAsyncResult(key, async () => {
    const structures: Record<string, TerrainStructureInput> = {}
    for (const [i, name] of names.entries()) {
      const path = paths[i]!
      if (!files.includes(path)) continue
      try {
        const structure = parseStructure(
          await readNbt(await readProjectBytes(backend, path, stamps[path])),
        )
        structures[name] = structureInput(structure)
      } catch {
        // Not a structure: not placed, as the server places none.
      }
    }
    return { structures, key }
  })
  if (names.length === 0) return { structures: {}, key: '' }
  return loaded?.value?.key === key ? loaded.value : null
}

const NO_SOURCES = { sources: {}, key: '' }
const NO_BLOCKS: readonly string[] = []

/**
 * The Lua terrain [id]'s script may run (its own file and the modules'), when [scripted]: an open document's text
 * as it is in the editor, else the file on disk (read again when its stamp changes).
 */
function useScriptSources(
  id: string,
  scripted: boolean,
): { sources: Record<string, string>; key: string } | null {
  const { backend } = useApp()
  const files = useWorkspace((s) => s.files)
  const stamps = useWorkspace((s) => s.fileStamps)
  const docs = useWorkspace((s) => s.docs)
  const paths = scripted ? scriptPaths(id, files) : []
  const open = (path: string) => {
    const doc = docs[path]
    return doc && !doc.deleted && !isModel(doc) ? doc.text : undefined
  }
  const key = paths
    .map((path) => {
      const text = open(path)
      return text === undefined ? `${path}@${stamps[path] ?? 0}` : `${path}#${text}`
    })
    .join('|')
  const loaded = useAsyncResult(key, async () => {
    const sources: Record<string, string> = {}
    for (const path of paths) {
      try {
        sources[path] = open(path) ?? (await backend.readText(path))
      } catch {
        // Gone meanwhile: a require of it says there's no such module, as on the server.
      }
    }
    return { sources, key }
  })
  if (!scripted) return NO_SOURCES
  return loaded?.value?.key === key ? loaded.value : null
}

/**
 * The heights the preview can draw a world of: the overworld's, and each `netherforge.json` world's that names this
 * terrain and a project dimension type, from the manifest and the dimension files as they are now (an open
 * document's edits included).
 */
function useHeightChoices(terrain: string): HeightChoice[] {
  const home = useHomeNamespace()
  const files = useWorkspace((s) => s.files)
  const docs = useWorkspace((s) => s.docs)
  const diskTexts = useWorkspace((s) => s.diskTexts)
  return useMemo(() => {
    const texts = { docs, diskTexts }
    const manifest = parseCached<ProjectManifest>(currentText(texts, MANIFEST_FILE))
    const dimensions: Record<string, DimensionTypeFile | null> = {}
    for (const id of resourceIdsOf(files, 'dimension_type')) {
      dimensions[id] = parseCached<DimensionTypeFile>(
        currentText(texts, mainFileIn(files, 'dimension_type', id)),
      )
    }
    return heightChoices(manifest, home, terrain, dimensions)
  }, [home, files, docs, diskTexts, terrain])
}

/** A tile scheduler of the editor's own, its workers started while it's mounted. */
function useTileScheduler(): TileScheduler {
  const [scheduler] = useState(
    () =>
      new TileScheduler(() =>
        Array.from({ length: tileLaneCount() }, () => new Lane(terrainPreviewWorker)),
      ),
  )
  useEffect(() => {
    scheduler.open()
    return () => scheduler.close()
  }, [scheduler])
  return scheduler
}

export function TerrainEditor({ path }: { path: string }) {
  const { workspace } = useApp()
  const { doc, model } = useModelDoc<TerrainFile>(path)
  const view = useView('terrain', path)
  const state = useViewState('terrain', path)
  useFocusRequests(path, (segments) => segments)
  const id = idOf(path)
  const choices = useHeightChoices(id)
  const height = pickHeights(choices, state.heights)

  // What the preview shows is the file as the editor holds it, drawn by the workers.
  const text = useMemo(() => (model ? JSON.stringify(model) : ''), [model])
  const named = useMemo(() => structuresNamed(model?.decorations), [model])
  const structures = useStructures(named)
  const scripts = useScriptSources(id, hasScript(text))
  const blocks = useWorkspace((s) => s.outline?.resources.block ?? NO_BLOCKS)
  const file = useMemo<PreviewFile | null>(
    () =>
      text && structures && scripts
        ? {
            id,
            text,
            structures: structures.structures,
            structuresKey: structures.key,
            sources: scripts.sources,
            sourcesKey: scripts.key,
            blocks,
          }
        : null,
    [id, text, structures, scripts, blocks],
  )
  const fileKey = useMemo(() => (file ? fileKeyOf(file) : ''), [file])
  const world = useMemo<PreviewWorld>(
    () => ({ seed: String(state.seed), minY: height.minY, maxY: height.maxY }),
    [state.seed, height.minY, height.maxY],
  )

  const scheduler = useTileScheduler()
  useEffect(() => {
    scheduler.setSource(file ? { fileKey, file, world } : null)
  }, [scheduler, file, fileKey, world])
  const status = useSyncExternalStore(scheduler.subscribe, () => scheduler.status)

  const sliceLine = useMemo<SliceLine>(
    () => ({ alongX: state.alongX, at: state.at, along: state.along, width: state.width }),
    [state.alongX, state.at, state.along, state.width],
  )
  const sliceAsk = useMemo<SliceAsk | null>(
    () =>
      file
        ? {
            file,
            request: {
              fileKey,
              world,
              alongX: sliceLine.alongX,
              at: sliceLine.at,
              from: sliceLine.along - Math.floor(sliceLine.width / 2),
              width: sliceLine.width,
            },
          }
        : null,
    [file, fileKey, world, sliceLine],
  )
  const slicePreview = useSlicePreview(sliceAsk)
  const slice = slicePreview.answer

  const camera = useMemo<MapCamera>(
    () => ({ x: state.x, z: state.z, zoom: state.zoom }),
    [state.x, state.z, state.zoom],
  )
  const onCamera = useCallback((it: MapCamera) => view.update(it), [view])
  const onCut = useCallback(
    (alongX: boolean, at: number, along: number) => view.update({ alongX, at, along }),
    [view],
  )
  const inspector = useMemo(
    () =>
      model && (
        <TerrainInspector
          path={path}
          model={model}
          edit={(recipe) => workspace.getState().edit<TerrainFile>(path, recipe)}
        />
      ),
    [workspace, path, model],
  )

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const failed = status.failed ?? (slice?.type === 'failed' ? slice.problems : null)
  const scriptErrors = scriptErrorsOf(status.scriptErrors, slice)
  const error = status.error ?? slicePreview.error
  return (
    <EditorScreen>
      <EditorBar title={id}>
        <label className={styles.seed}>
          Seed
          <NumberInput
            label="Seed"
            value={state.seed}
            step={1}
            onChange={(value) => view.update({ seed: Math.round(value ?? 1) })}
          />
        </label>
        <Button
          icon="refresh"
          onClick={() => view.update({ seed: Math.floor(Math.random() * 2 ** 31) })}
        >
          Random seed
        </Button>
        {choices.length > 1 && (
          <label className={styles.seed}>
            Heights
            <select
              aria-label="Preview heights"
              value={height.key}
              onChange={(event) => view.update({ heights: event.target.value })}
            >
              {choices.map((choice) => (
                <option key={choice.key} value={choice.key}>
                  {choice.label}
                </option>
              ))}
            </select>
          </label>
        )}
        <label className={styles.seed}>
          Detail
          <select
            aria-label="Map detail"
            value={state.detail}
            onChange={(event) => view.update({ detail: Number(event.target.value) })}
          >
            {DETAILS.map((it) => (
              <option key={it.value} value={it.value}>
                {it.label}
              </option>
            ))}
          </select>
        </label>
        <EditAsJson path={path} />
      </EditorBar>
      <div className={styles.body} data-testid="terrain-previews">
        {failed && (
          <section className={styles.failed} role="alert" aria-label="The preview can't draw this">
            <strong>The terrain can't be drawn yet:</strong>
            <ul>
              {failed.slice(0, 5).map((problem, index) => (
                <li key={index}>{problem.message}</li>
              ))}
            </ul>
          </section>
        )}
        {/* Over the map, so it never changes size under the pointer. */}
        <div className={styles.alerts}>
          {!failed && scriptErrors.length > 0 && <ScriptErrors errors={scriptErrors} />}
          {!failed && error && (
            <section className={styles.failed} role="alert" aria-label="The preview stopped">
              <strong>The preview stopped:</strong> {error}
            </section>
          )}
        </div>
        {failed ? (
          <Empty>Nothing to draw until the file is fixed.</Empty>
        ) : (
          <Group orientation="vertical" className={styles.split}>
            <Panel id="terrain-map-pane" defaultSize="68" minSize="20" className={styles.pane}>
              <MapView
                scheduler={scheduler}
                camera={camera}
                detail={state.detail}
                onCamera={onCamera}
                slice={sliceLine}
                onCut={onCut}
              />
            </Panel>
            <Splitter label="Resize the slice" />
            <Panel id="terrain-slice-pane" defaultSize="32" minSize="12" className={styles.pane}>
              <SliceView
                slice={slice?.type === 'slice' ? slice : null}
                fluid={model.terrain?.fluid ?? TERRAIN_DEFAULTS.fluid}
                stone={model.stone?.block ?? TERRAIN_DEFAULTS.stone}
                drawing={slicePreview.drawing}
                tools={
                  <>
                    <label className={styles.seed}>
                      Along
                      <select
                        aria-label="Slice along"
                        value={state.alongX ? 'x' : 'z'}
                        onChange={(event) => view.update({ alongX: event.target.value === 'x' })}
                      >
                        <option value="x">the x axis</option>
                        <option value="z">the z axis</option>
                      </select>
                    </label>
                    <label className={styles.seed}>
                      Width
                      <select
                        aria-label="Slice width"
                        value={state.width}
                        onChange={(event) => view.update({ width: Number(event.target.value) })}
                      >
                        {WIDTHS.map((it) => (
                          <option key={it} value={it}>
                            {it} blocks
                          </option>
                        ))}
                      </select>
                    </label>
                  </>
                }
              />
            </Panel>
          </Group>
        )}
      </div>
      <InspectorPanel>{inspector}</InspectorPanel>
    </EditorScreen>
  )
}

// ---- the script -----------------------------------------------------------------------------------

/** What the script failed at in the map's tiles or the slice, each once. */
function scriptErrorsOf(
  map: TerrainScriptError[],
  slice: TerrainPreviewResult | null,
): TerrainScriptError[] {
  const seen = new Map<string, TerrainScriptError>()
  const all = slice?.type === 'slice' ? [...map, ...slice.scriptErrors] : map
  for (const error of all) seen.set(`${error.stage}\n${error.message}`, error)
  return [...seen.values()]
}

/** The script's failures, each opening the file at its line: what failed is drawn as the file alone makes it. */
function ScriptErrors({ errors }: { errors: TerrainScriptError[] }) {
  const { workspace } = useApp()
  return (
    <section className={styles.failed} role="alert" aria-label="The script failed">
      <strong>The script failed, so the file's own result is drawn there:</strong>
      <ul>
        {errors.slice(0, 5).map((error) => (
          <li key={`${error.stage}\n${error.message}`}>
            <Button
              variant="link"
              onClick={() =>
                void workspace.getState().openFile(error.file, { line: error.line ?? undefined })
              }
            >
              {error.stage === 'load' ? 'loading it' : `its ${error.stage} stage`}
            </Button>
            : {error.message}
          </li>
        ))}
      </ul>
    </section>
  )
}
