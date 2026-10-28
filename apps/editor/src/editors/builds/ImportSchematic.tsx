/**
 * Importing a WorldEdit (`.schem`) or Litematica (`.litematic`) schematic into a structure's one file or a
 * build's parts, read and cut by `@netherforge/structures` (`schematic.ts`, `parts.ts`), as
 * `netherforge import` does. The schematic's data version goes into the files, so the server renames what an
 * older version called otherwise as it reads them; the blocks the imported game data doesn't know are named, and
 * entities, which an import doesn't carry, are counted.
 */
import { useRef, useState } from 'react'
import { BUILD_LIMITS } from '@netherforge/format/constants'
import { readNbt } from '@netherforge/structures/nbt'
import {
  buildParts,
  fitsOneFile,
  gridOf,
  structureFile,
  unknownBlocks,
} from '@netherforge/structures/parts'
import { readSchematic, type Volume } from '@netherforge/structures/schematic'
import { useApp, useWorkspace } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { Section } from '@/ui/fields'
import { Button } from '@/ui/Button'
import { Hint } from '@/ui/text'
import { Actions } from '@/editors/structure/parts'

export type ImportTarget =
  | { kind: 'structure'; path: string; exists: boolean }
  /** A build's folder, and the part files it has now (replaced). */
  | { kind: 'build'; folder: string; parts: readonly string[] }

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error))

/** What an import says it did: the box, what it kept and left out, the blocks the game doesn't know. */
function summary(
  volume: Volume,
  where: string,
  unknown: { id: string; count: number }[] | null,
): string[] {
  const from = volume.format === 'sponge' ? 'WorldEdit' : 'Litematica'
  const lines = [
    `Imported ${volume.size.join(' × ')} blocks from a ${from} schematic into ${where}` +
      (volume.dataVersion !== null ? ` (data version ${volume.dataVersion}).` : '.'),
  ]
  if (volume.blockEntities.size > 0)
    lines.push(`${volume.blockEntities.size} block entities came with it.`)
  if (volume.entities > 0)
    lines.push(`${volume.entities} entities were left out: an import carries blocks only.`)
  if (unknown === null) {
    lines.push(
      "Its blocks weren't checked against the game: start the dev server once to import the game's data.",
    )
  } else if (unknown.length > 0) {
    lines.push(
      `The game doesn't have ${unknown.map((it) => `${it.id} (${it.count})`).join(', ')}: an older version's blocks ` +
        "the server renames as it reads them, or a mod's, which it leaves as air.",
    )
  }
  return lines
}

export function ImportSchematic({ target }: { target: ImportTarget }) {
  const { workspace } = useApp()
  const gameData = useWorkspace((s) => s.gameData)
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<{ kind: 'error' | 'info'; lines: string[] } | null>(null)
  const where = target.kind === 'structure' ? target.path : `${target.folder}/`

  const run = async (file: File) => {
    setMessage(null)
    let volume: Volume
    try {
      volume = readSchematic(await readNbt(new Uint8Array(await file.arrayBuffer())))
    } catch (error) {
      setMessage({ kind: 'error', lines: [`${file.name} can't be imported: ${errorText(error)}`] })
      return
    }
    const size = volume.size.join(' × ')
    if (target.kind === 'structure' && !fitsOneFile(volume.size)) {
      setMessage({
        kind: 'error',
        lines: [
          `${file.name} is ${size} blocks, over ${BUILD_LIMITS.partSide} along a side: too big for a structure. Import it on a build's screen (Builds in the explorer) instead.`,
        ],
      })
      return
    }
    if (target.kind === 'build' && gridOf(volume.size).some((it) => it > BUILD_LIMITS.maxParts)) {
      setMessage({
        kind: 'error',
        lines: [
          `${file.name} is ${size} blocks; a build is at most ${BUILD_LIMITS.maxParts * BUILD_LIMITS.partSide} along each side.`,
        ],
      })
      return
    }
    const replacing = target.kind === 'structure' ? target.exists : target.parts.length > 0
    if (replacing) {
      const ok = await ask.confirm({
        title: 'Replace it',
        message: `Replace ${where} with ${file.name}? Git can bring the old one back if it was committed.`,
        confirmLabel: 'Replace',
        danger: true,
      })
      if (ok !== true) return
    }
    const store = workspace.getState()
    setBusy('Importing…')
    try {
      if (target.kind === 'structure') {
        await store.writeBinary(target.path, await structureFile(volume, [0, 0, 0], volume.size))
      } else {
        const written = new Set<string>()
        for await (const part of buildParts(volume, (done, total) =>
          setBusy(`Writing part ${done} of ${total}…`),
        )) {
          await store.writeBinary(`${target.folder}/${part.file}`, part.bytes)
          written.add(part.file)
        }
        // What the old grid had past the new one goes.
        for (const old of target.parts)
          if (!written.has(old)) await store.deletePath(`${target.folder}/${old}`)
      }
      const blocks = gameData?.blocks
      const known = blocks ? (id: string) => id in blocks : null
      setMessage({
        kind: 'info',
        lines: summary(volume, where, known ? unknownBlocks(volume, known) : null),
      })
    } catch (error) {
      setMessage({ kind: 'error', lines: [`Couldn't import ${file.name}: ${errorText(error)}`] })
    } finally {
      setBusy(null)
    }
  }

  return (
    <Section title="Import a schematic" icon="box">
      <Hint>
        A WorldEdit .schem or Litematica .litematic file
        {target.kind === 'structure'
          ? `, up to ${BUILD_LIMITS.partSide} blocks a side.`
          : ', of any size a build holds.'}
      </Hint>
      <Actions>
        <Button
          size="small"
          icon="plus"
          disabled={busy !== null}
          onClick={() => input.current?.click()}
        >
          Import…
        </Button>
      </Actions>
      <input
        ref={input}
        type="file"
        accept=".schem,.litematic"
        hidden
        aria-label="Schematic to import"
        onChange={(event) => {
          const file = event.target.files?.[0]
          event.target.value = ''
          if (file) void run(file)
        }}
      />
      {busy && <Hint role="status">{busy}</Hint>}
      {message && (
        <Hint tone={message.kind === 'error' ? 'error' : undefined} role="status">
          {message.lines.join(' ')}
        </Hint>
      )}
    </Section>
  )
}
