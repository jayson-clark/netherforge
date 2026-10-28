/**
 * A structure's screen: the 3D preview, what the file holds (size, blocks by
 * kind, entities, data version), and capturing it from the dev server. The
 * file is Minecraft's binary structure format, read here (`nbt/`), never
 * edited: a capture replaces it whole. The plugin saves the box and answers
 * its bytes over the bridge; the editor writes `structures/<id>.nbt` itself
 * (the server never writes project files), and the save's hot reload makes
 * the next placement read it. Beside it, "Generates in the world" edits
 * `structures/<id>.json` (`Generation`): where the game places it by itself.
 */
import { useState } from 'react'
import type { PlayerPosition } from '@netherforge/format/types'
import { base64ToBytes } from '@/core/backend/base64'
import { readNbt } from '@netherforge/structures/nbt'
import { companionOf, resourceOf } from '@/core/paths'
import { useApp, useRun, useWorkspace } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { CheckField, Row, Section, SelectField, Vec3Field } from '@/ui/fields'
import { Button } from '@/ui/Button'
import { Hint } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import { ViewportFallback } from '@/editors/shared/viewport/parts'
import { Actions, CountsTable } from './parts'
import { readProjectBytes } from '@/core/backend/projectBytes'
import { useLoadedWorlds, WorldSelect } from '@/editors/shared/ServerWorlds'
import { useAsyncResult } from '@/ui/useAsync'
import type { BlockMesh } from '@/minecraft/client/blockMesh'
import { blockCounts, parseStructure, type Structure } from '@netherforge/structures/structure'
import { ImportSchematic } from '@/editors/builds/ImportSchematic'
import { BUILD_LIMITS } from '@netherforge/format/constants'
import { Generation } from './Generation'
import { StructureView, type Built } from './StructureView'

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error))

export function StructureEditor({ path }: { path: string }) {
  const { backend } = useApp()
  const found = resourceOf(path)
  const id = found?.id ?? path
  const exists = useWorkspace((s) => s.files.includes(path))
  const generationPath = companionOf('structure', id, found?.group) ?? ''
  const generates = useWorkspace((s) => s.files.includes(generationPath))
  const stamp = useWorkspace((s) => s.fileStamps[path] ?? 0)
  const loaded = useAsyncResult(`${path}|${stamp}|${exists}`, () =>
    exists
      ? readProjectBytes(backend, path, stamp)
          .then(readNbt)
          .then((root) => parseStructure(root))
      : null,
  )
  const structure = loaded?.value ?? null
  const [built, setBuilt] = useState<Built | null>(null)
  const size = structure?.size

  return (
    <EditorScreen>
      <EditorBar title={path}>
        {size && (
          <span aria-label="Structure size">
            {size[0]} × {size[1]} × {size[2]}
          </span>
        )}
      </EditorBar>
      {structure ? (
        <StructureView
          path={path}
          structure={structure}
          version={`${stamp}`}
          built={built}
          onBuilt={setBuilt}
        />
      ) : (
        <ViewportFallback>
          <span role="status">
            {!exists
              ? `There's no ${path} yet. Capture it from the dev server.`
              : loaded?.error
                ? `${path} can't be read as a structure: ${loaded.error}`
                : 'Reading…'}
          </span>
        </ViewportFallback>
      )}
      <InspectorPanel label="Structure">
        {structure && <StructureInfo structure={structure} mesh={built?.mesh ?? null} />}
        {exists && <Generation id={id} path={generationPath} exists={generates} />}
        <CaptureStructure id={id} path={path} exists={exists} />
        <ImportSchematic target={{ kind: 'structure', path, exists }} />
      </InspectorPanel>
    </EditorScreen>
  )
}

function StructureInfo({ structure, mesh }: { structure: Structure; mesh: BlockMesh | null }) {
  const counts = blockCounts(structure)
  const [sx, sy, sz] = structure.size
  return (
    <>
      <Section title="Structure" icon="structure">
        <Row label="Size">
          <span>
            {sx} × {sy} × {sz} blocks
          </span>
        </Row>
        <Row label="Blocks">
          <span aria-label="Block count">{structure.blocks.length / 4}</span>
        </Row>
        <Row label="Entities">
          <span aria-label="Entity count">{structure.entities}</span>
        </Row>
        <Row label="Block entities">
          <span>{structure.blockEntities}</span>
        </Row>
        <Row label="Data version">
          <span aria-label="Data version">{structure.dataVersion ?? 'not given'}</span>
        </Row>
        {structure.otherPalettes > 0 && (
          <Hint>
            It has {structure.otherPalettes + 1} palettes; the game picks one each time it's placed.
            The preview shows the first.
          </Hint>
        )}
        {mesh && (
          <Hint aria-label="Faces drawn">
            Drawing {mesh.faces} faces ({mesh.culled} hidden ones left out).
          </Hint>
        )}
      </Section>
      <Section title="Blocks by kind" icon="blocks">
        <CountsTable label="Blocks by kind" rows={counts.map(({ kind, count }) => [kind, count])} />
      </Section>
    </>
  )
}

type Corner = [number, number, number]

/** Capturing a box from the dev server into `structures/<id>.nbt`. */
function CaptureStructure({ id, path, exists }: { id: string; path: string; exists: boolean }) {
  const { backend, workspace } = useApp()
  const connected = useRun((s) => s.server.bridgeConnected)
  const players = useRun((s) => s.players)
  const [from, setFrom] = useState<Corner>([0, 0, 0])
  const [to, setTo] = useState<Corner>([0, 0, 0])
  const [player, setPlayer] = useState('')
  const [world, setWorld] = useState('')
  const [entities, setEntities] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ kind: 'error' | 'info'; text: string } | null>(null)
  const { worlds, refresh } = useLoadedWorlds(connected)
  const chosenWorld = world || worlds.find((it) => it.main)?.name || worlds[0]?.name || ''
  const chosenPlayer = players.includes(player) ? player : (players[0] ?? '')

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
    if (exists) {
      const ok = await ask.confirm({
        title: `Replace ${id}`,
        message: `Replace ${path} with the blocks captured now? Git can bring the old one back if it was committed.`,
        confirmLabel: 'Replace',
        danger: true,
      })
      if (ok !== true) return
    }
    setBusy(true)
    try {
      const saved = await backend.bridgeRequest('save_structure', {
        world: chosenWorld,
        from: { x: from[0], y: from[1], z: from[2] },
        to: { x: to[0], y: to[1], z: to[2] },
        entities,
      })
      const bytes = base64ToBytes(saved.nbt)
      // Check it reads before it replaces anything.
      parseStructure(await readNbt(bytes))
      if (await workspace.getState().writeBinary(path, bytes)) {
        const { x, y, z } = saved.size
        setMessage({ kind: 'info', text: `Captured ${x} × ${y} × ${z} blocks into ${path}.` })
      }
    } catch (error) {
      setMessage({ kind: 'error', text: `Couldn't capture: ${errorText(error)}` })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Section title="Capture from the dev server" icon="camera">
      {!connected && <Hint>Start the dev server to capture a structure from its world.</Hint>}
      {players.length > 1 && (
        <SelectField
          label="Player"
          value={chosenPlayer}
          options={players}
          onChange={(next) => setPlayer(next)}
        />
      )}
      <CornerField
        label="Corner 1"
        value={from}
        onChange={setFrom}
        onUsePosition={() => void takePosition(setFrom)}
        disabled={!connected}
      />
      <CornerField
        label="Corner 2"
        value={to}
        onChange={setTo}
        onUsePosition={() => void takePosition(setTo)}
        disabled={!connected}
      />
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
          {Math.abs(from[0] - to[0]) + 1} × {Math.abs(from[1] - to[1]) + 1} ×{' '}
          {Math.abs(from[2] - to[2]) + 1} blocks
        </span>
      </Row>
      {[0, 1, 2].some((i) => Math.abs(from[i]! - to[i]!) + 1 > BUILD_LIMITS.partSide) && (
        <Hint tone="error">
          A structure is at most {BUILD_LIMITS.partSide} blocks a side. Capture a bigger box as a
          build (Builds in the explorer), which scripts place with world:place_gradually.
        </Hint>
      )}
      <Actions>
        <Button
          variant="primary"
          size="small"
          icon="save"
          disabled={!connected || busy || !chosenWorld}
          onClick={() => void capture()}
        >
          {exists ? 'Capture and replace' : 'Capture'}
        </Button>
      </Actions>
      {message && (
        <Hint tone={message.kind === 'error' ? 'error' : undefined} role="status">
          {message.text}
        </Hint>
      )}
    </Section>
  )
}

function CornerField({
  label,
  value,
  onChange,
  onUsePosition,
  disabled,
}: {
  label: string
  value: Corner
  onChange: (corner: Corner) => void
  onUsePosition: () => void
  disabled: boolean
}) {
  return (
    <>
      <Vec3Field
        label={label}
        value={value}
        defaultValue={[0, 0, 0]}
        step={1}
        onChange={(next) => onChange(next.map((it) => Math.round(it)) as Corner)}
      />
      <Actions>
        <Button
          size="small"
          disabled={disabled}
          aria-label={`Use my position for ${label.toLowerCase()}`}
          onClick={onUsePosition}
        >
          Use my position
        </Button>
      </Actions>
    </>
  )
}
