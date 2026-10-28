/**
 * A build's screen (`builds/<id>/`, a structure too big for one file, kept as a grid of parts): a picture of it
 * from above, isometric or sliced (the same pixels as `netherforge preview`), what its parts make, and the two
 * ways one is made: capturing a box of any size from the dev server, a part at a time (each part is the bridge's
 * `save_structure` of its box, written here: the server never writes project files), or importing a schematic.
 * Its parts are Minecraft's binary structure files, read here, never edited: a capture or import replaces them
 * all. Scripts place it with `world:place_gradually`.
 */
import { useMemo, useState } from 'react'
import type { PlayerPosition } from '@netherforge/format/types'
import { BUILD_LIMITS } from '@netherforge/format/constants'
import { readNbt } from '@netherforge/structures/nbt'
import { gridOfFiles } from '@netherforge/structures/parts'
import type { View } from '@netherforge/structures/picture'
import { parseStructure } from '@netherforge/structures/structure'
import { base64ToBytes } from '@/core/backend/base64'
import { readProjectBytes } from '@/core/backend/projectBytes'
import { resourceOf } from '@/core/paths'
import { useApp, useRun, useWorkspace } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { CheckField, NumberField, Row, Section, SelectField, Vec3Field } from '@/ui/fields'
import { Button } from '@/ui/Button'
import { Hint } from '@/ui/text'
import { useAsyncResult } from '@/ui/useAsync'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import { useLoadedWorlds, WorldSelect } from '@/editors/shared/ServerWorlds'
import { Actions } from '@/editors/structure/parts'
import { ImportSchematic } from './ImportSchematic'
import {
  boxSize,
  buildSize,
  drawBuild,
  fitView,
  lastPart,
  missingParts,
  partBoxes,
  partNames,
  tooBig,
  type Corner,
} from './ops'
import { PictureView } from './PictureView'

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error))

type ViewChoice = 'top' | 'iso' | 'x' | 'y' | 'z'

const VIEWS: { value: ViewChoice; label: string }[] = [
  { value: 'top', label: 'From above' },
  { value: 'iso', label: 'Isometric' },
  { value: 'y', label: 'A layer (y)' },
  { value: 'x', label: 'A slice along x' },
  { value: 'z', label: 'A slice along z' },
]

const viewOf = (choice: ViewChoice, at: number): View =>
  choice === 'top' || choice === 'iso' ? { kind: choice } : { kind: 'slice', axis: choice, at }

export function BuildEditor({ path }: { path: string }) {
  const { backend } = useApp()
  const found = resourceOf(path)
  const id = found?.id ?? path
  // The tab's path is the first part; the build is its folder.
  const folder = path.slice(0, path.lastIndexOf('/'))
  const files = useWorkspace((s) => s.files)
  const names = useMemo(
    () =>
      partNames(
        files.filter((it) => it.startsWith(`${folder}/`)).map((it) => it.slice(folder.length + 1)),
      ),
    [files, folder],
  )
  const stamps = useWorkspace((s) =>
    names.map((it) => s.fileStamps[`${folder}/${it}`] ?? 0).join(','),
  )
  const exists = names.includes(path.slice(folder.length + 1))
  const missing = useMemo(() => missingParts(names), [names])
  const [choice, setChoice] = useState<ViewChoice>('top')
  const [at, setAt] = useState(0)

  const read = (file: string) =>
    readProjectBytes(backend, `${folder}/${file}`)
      .then(readNbt)
      .then((root) => parseStructure(root))
  const size = useAsyncResult(`${folder}|${stamps}|size`, () =>
    exists && missing.length === 0
      ? read(lastPart(names)).then((last) => buildSize(names, last))
      : null,
  )?.value
  const view = size ? fitView(viewOf(choice, at), size) : viewOf(choice, at)
  const drawn = useAsyncResult(
    `${folder}|${stamps}|${JSON.stringify(view)}|${size?.join(',')}`,
    () => (size ? drawBuild(size, names, view, read) : null),
  )

  const status = !exists
    ? `There's no ${folder}/ yet. Capture it from the dev server, or import a schematic.`
    : missing.length > 0
      ? `${folder}/ is missing ${missing.join(', ')}: its parts don't make a whole grid.`
      : drawn?.error
        ? `${folder}/ can't be drawn: ${drawn.error}`
        : 'Reading its parts…'
  const grid = gridOfFiles(names)
  const limit = size ? size[{ x: 0, y: 1, z: 2, top: 1, iso: 1 }[choice]]! : 1

  return (
    <EditorScreen>
      <EditorBar title={`${folder}/`}>
        {size && <span aria-label="Build size">{size.join(' × ')}</span>}
      </EditorBar>
      <PictureView picture={drawn?.value ?? null} label="Build picture" status={status} />
      <InspectorPanel label="Build">
        {exists && (
          <Section title="Build" icon="blocks">
            <Row label="Size">
              <span>{size ? `${size.join(' × ')} blocks` : '…'}</span>
            </Row>
            <Row label="Parts">
              <span aria-label="Part count">
                {names.length} ({grid.join(' × ')}, {BUILD_LIMITS.partSide} a side)
              </span>
            </Row>
            <Hint>
              Scripts place it a few thousand blocks a tick: world:place_gradually("{id}",
              position).
            </Hint>
          </Section>
        )}
        {exists && (
          <Section title="Picture" icon="image">
            <SelectField label="View" value={choice} options={VIEWS} onChange={setChoice} />
            {choice !== 'top' && choice !== 'iso' && (
              <NumberField
                label={choice === 'y' ? 'Layer' : choice === 'x' ? 'At x' : 'At z'}
                value={view.kind === 'slice' ? view.at : 0}
                step={1}
                min={0}
                onChange={(next) => setAt(Math.max(0, Math.min(limit - 1, Math.round(next ?? 0))))}
              />
            )}
          </Section>
        )}
        <CaptureBuild folder={folder} parts={names} />
        <ImportSchematic target={{ kind: 'build', folder, parts: names }} />
      </InspectorPanel>
    </EditorScreen>
  )
}

/** Capturing a box of any size from the dev server into the build's parts, one part's box at a time. */
function CaptureBuild({ folder, parts }: { folder: string; parts: readonly string[] }) {
  const { backend, workspace } = useApp()
  const connected = useRun((s) => s.server.bridgeConnected)
  const players = useRun((s) => s.players)
  const [from, setFrom] = useState<Corner>([0, 0, 0])
  const [to, setTo] = useState<Corner>([0, 0, 0])
  const [player, setPlayer] = useState('')
  const [world, setWorld] = useState('')
  const [entities, setEntities] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<{ kind: 'error' | 'info'; text: string } | null>(null)
  const { worlds, refresh } = useLoadedWorlds(connected)
  const chosenWorld = world || worlds.find((it) => it.main)?.name || worlds[0]?.name || ''
  const chosenPlayer = players.includes(player) ? player : (players[0] ?? '')
  const size = boxSize(from, to)
  const big = tooBig(size)

  const takePosition = async (set: (corner: Corner) => void) => {
    setMessage(null)
    try {
      const at: PlayerPosition = await backend.bridgeRequest(
        'player_position',
        chosenPlayer ? { player: chosenPlayer } : {},
      )
      set([at.x, at.y, at.z])
      setWorld(at.world)
    } catch (error) {
      setMessage({ kind: 'error', text: `Couldn't find a player: ${errorText(error)}` })
    }
  }

  const capture = async () => {
    setMessage(null)
    if (parts.length > 0) {
      const ok = await ask.confirm({
        title: 'Replace the build',
        message: `Replace ${folder}/ with the blocks captured now? Git can bring the old parts back if they were committed.`,
        confirmLabel: 'Replace',
        danger: true,
      })
      if (ok !== true) return
    }
    const boxes = partBoxes(from, to)
    const store = workspace.getState()
    try {
      // Every part is captured before any is written, so a capture that fails part way changes nothing.
      const captured: { file: string; bytes: Uint8Array }[] = []
      for (const [index, box] of boxes.entries()) {
        setBusy(`Capturing part ${index + 1} of ${boxes.length}…`)
        const saved = await backend.bridgeRequest('save_structure', {
          world: chosenWorld,
          from: box.from,
          to: box.to,
          entities,
        })
        const bytes = base64ToBytes(saved.nbt)
        parseStructure(await readNbt(bytes))
        captured.push({ file: box.file, bytes })
      }
      setBusy('Writing the parts…')
      for (const part of captured) await store.writeBinary(`${folder}/${part.file}`, part.bytes)
      const kept = new Set(captured.map((it) => it.file))
      for (const old of parts) if (!kept.has(old)) await store.deletePath(`${folder}/${old}`)
      setMessage({
        kind: 'info',
        text: `Captured ${size.join(' × ')} blocks into ${captured.length} parts in ${folder}/.`,
      })
    } catch (error) {
      setMessage({ kind: 'error', text: `Couldn't capture: ${errorText(error)}` })
    } finally {
      setBusy(null)
    }
  }

  return (
    <Section title="Capture from the dev server" icon="camera">
      {!connected && <Hint>Start the dev server to capture a build from its world.</Hint>}
      {players.length > 1 && (
        <SelectField
          label="Player"
          value={chosenPlayer}
          options={players}
          onChange={(next) => setPlayer(next)}
        />
      )}
      {(
        [
          ['Corner 1', from, setFrom],
          ['Corner 2', to, setTo],
        ] as const
      ).map(([label, value, set]) => (
        <div key={label}>
          <Vec3Field
            label={label}
            value={value}
            defaultValue={[0, 0, 0]}
            step={1}
            onChange={(next) => set(next.map((it) => Math.round(it)) as Corner)}
          />
          <Actions>
            <Button
              size="small"
              disabled={!connected}
              aria-label={`Use my position for ${label.toLowerCase()}`}
              onClick={() => void takePosition(set)}
            >
              Use my position
            </Button>
          </Actions>
        </div>
      ))}
      <WorldSelect
        worlds={worlds}
        value={chosenWorld}
        onChange={setWorld}
        onRefresh={refresh}
        disabled={!connected}
      />
      <CheckField label="Entities" value={entities} onChange={setEntities} />
      <Row label="Box">
        <span>
          {size.join(' × ')} blocks, {partBoxes(from, to).length} parts
        </span>
      </Row>
      {big && (
        <Hint tone="error">
          A build is at most {BUILD_LIMITS.maxParts * BUILD_LIMITS.partSide} blocks along each side.
        </Hint>
      )}
      <Actions>
        <Button
          variant="primary"
          size="small"
          icon="save"
          disabled={!connected || busy !== null || !chosenWorld || big}
          onClick={() => void capture()}
        >
          {parts.length > 0 ? 'Capture and replace' : 'Capture'}
        </Button>
      </Actions>
      {busy && <Hint role="status">{busy}</Hint>}
      {message && (
        <Hint tone={message.kind === 'error' ? 'error' : undefined} role="status">
          {message.text}
        </Hint>
      )}
    </Section>
  )
}
