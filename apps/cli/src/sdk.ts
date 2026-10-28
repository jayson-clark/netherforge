/**
 * What a project's own generators (`tools/*.mjs`, run by `netherforge generate`) write the project with: where its
 * resources are, where a new one goes, and writing files the way the editor and `netherforge format` would, so a run
 * needs no format after it and a generator never re-implements the project's layout. Imported from the CLI itself:
 *
 *   import { project } from "../.netherforge/bin/netherforge.mjs"
 *   const p = project()
 *   p.writeResource("items", "ruby", { kind: "minecraft:paper", name: "Ruby" }, { group: "gems" })
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import * as nf from '@netherforge/format'
import type { CanonicalResult, ClassifiedPath } from '@netherforge/format/types'
import { documentKindOf, findProjectRoot, listFiles } from './project.ts'

/** One of the project's resources: its kind's folder (`items`), its id, its group folders and its main file. */
export interface ProjectResource {
  kind: string
  id: string
  /** Its group folders as on disk, `(weapons)/(swords)`, or `''`. */
  group: string
  /** Its main file (a single-file kind's one file), from the project's folder. */
  path: string
}

export interface GeneratorProject {
  /** The project's folder. */
  root: string
  /** Every resource of a kind (by its folder: `items`, `centities`, `recipes`), by id, wherever its group folders put it. */
  resources(kind: string): Map<string, ProjectResource>
  /**
   * Where resource [id] of [kind]'s main file is: where it already is (so groups rearranged by hand stay), or else in
   * [group] folders (`"weapons/swords"` or `"(weapons)/(swords)"`).
   */
  place(kind: string, id: string, group?: string): string
  /** A project file's text, or null when there's none. */
  read(file: string): string | null
  /** A project file's JSON, or null when there's none. */
  readJson<T = unknown>(file: string): T | null
  /**
   * Writes a project file, its folders made: a JSON document of the project in canonical form, as the editor writes
   * it. An unchanged file isn't touched. Says whether it changed.
   */
  write(file: string, text: string): boolean
  /** Writes [value] as JSON to a project file, canonically. */
  writeJson(file: string, value: unknown): boolean
  /**
   * Writes resource [id] of [kind]: its main file from [value] (or, with `file`, that file inside its folder), where
   * [place] says. Answers the path written.
   */
  writeResource(
    kind: string,
    id: string,
    value: unknown,
    options?: { group?: string; file?: string },
  ): string
}

const KIND_ALIASES: Record<string, string> = {}

/** `weapons/swords` (or `(weapons)/(swords)`) as group folders are on disk. */
function groupFolders(group: string | undefined): string | null {
  const parts = (group ?? '').split('/').filter(Boolean)
  if (parts.length === 0) return null
  return parts.map((part) => (part.startsWith('(') ? part : `(${part})`)).join('/')
}

/** The project at or above [start] (default: the current folder), for a generator to write. */
export function project(start: string = process.cwd()): GeneratorProject {
  const root = findProjectRoot(start)
  if (!root) throw new Error(`No netherforge.json in ${start} or any folder above it.`)
  let byKind: Map<string, Map<string, ProjectResource>> | null = null

  const index = () => {
    if (byKind) return byKind
    byKind = new Map()
    for (const file of listFiles(root)) {
      const found = JSON.parse(nf.classify(file)) as ClassifiedPath | null
      if (!found || found.role !== 'main' || !found.kind || !found.id || found.package != null)
        continue
      const folder = file.split('/')[0]!
      KIND_ALIASES[folder] = found.kind
      let kind = byKind.get(folder)
      if (!kind) byKind.set(folder, (kind = new Map()))
      kind.set(found.id, { kind: folder, id: found.id, group: found.group ?? '', path: file })
    }
    return byKind
  }

  /** Format's kind id for a kind folder (`items` → `item`), from where a resource of it would go. */
  const kindIdOf = (folder: string): string => {
    const known = KIND_ALIASES[folder]
    if (known) return known
    for (const candidate of [folder.replace(/s$/, ''), folder]) {
      try {
        if (nf.resourcePath(candidate, 'x', null).startsWith(`${folder}/`)) {
          KIND_ALIASES[folder] = candidate
          return candidate
        }
      } catch {
        // Not a kind's id: try the next.
      }
    }
    throw new Error(`"${folder}" isn't a kind's folder`)
  }

  const self: GeneratorProject = {
    root,
    resources: (kind) => new Map(index().get(kind) ?? []),
    place(kind, id, group) {
      const found = index().get(kind)?.get(id)
      if (found) return found.path
      return nf.resourcePath(kindIdOf(kind), id, groupFolders(group))
    },
    read(file) {
      try {
        return readFileSync(path.join(root, file), 'utf8')
      } catch {
        return null
      }
    },
    readJson<T>(file: string) {
      const text = self.read(file)
      return text == null ? null : (JSON.parse(text) as T)
    },
    write(file, text) {
      const kind = documentKindOf(file)
      let out = text
      if (kind) {
        const result = JSON.parse(nf.canonicalize(kind, file, text)) as CanonicalResult
        // A file format can't read yet is written as it is: `netherforge check` says what's wrong with it.
        if (result.text != null) out = result.text
      }
      if (self.read(file) === out) return false
      const full = path.join(root, file)
      mkdirSync(path.dirname(full), { recursive: true })
      writeFileSync(full, out)
      return true
    },
    writeJson: (file, value) => self.write(file, `${JSON.stringify(value, null, 2)}\n`),
    writeResource(kind, id, value, options = {}) {
      const main = self.place(kind, id, options.group)
      const file = options.file ? `${path.posix.dirname(main)}/${options.file}` : main
      if (typeof value === 'string') self.write(file, value)
      else self.writeJson(file, value)
      if (!options.file) {
        const found = JSON.parse(nf.classify(main)) as ClassifiedPath | null
        const map = index().get(kind) ?? new Map<string, ProjectResource>()
        index().set(kind, map)
        map.set(id, { kind, id, group: found?.group ?? '', path: main })
      }
      return file
    },
  }
  return self
}
