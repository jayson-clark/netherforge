/**
 * The packages slice: the packages the project's dependencies resolve to
 * (git ones fetched into the package cache, every folder read and hashed
 * whenever the manifest or the lock changes, handed to format with every
 * validation), `netherforge.lock` kept what they resolve to, updating git
 * dependencies to their revs' newest commits, every package read-only as a
 * whole, and copying a package's resource in.
 */
import {
  canonicalize,
  documentKindOf,
  isPackageFile,
  loadProject,
  NO_PACKAGES,
  KINDS,
  LOCK_FILE,
  MANIFEST_FILE,
  moveRefs,
  packageHash,
  packagesNeeded,
  type KindId,
  type PackageFolder,
  type PackageInputs,
  type PackageSource,
  type ProjectManifest,
  type ProjectOutline,
} from '@/core/format'
import type { Backend, ProjectInfo, WorkspaceInfo, WorkspaceMember } from '@/core/backend/types'
import { templateById } from '@/core/templates'
import { isModel } from './documents'
import type { Workspace } from './workspace'
import { locationOf, readsText, resourceOf } from '@/core/paths'
import { errorText, isUnder, nextNonce, type SliceArgs } from './slice'
import { namespaceOf, projectInput } from './validation'

export interface PackagesState {
  /**
   * What the project's dependencies resolve to: every package folder by
   * location (`../library`, or `git:<commit>` in the package cache; null:
   * nothing there), read and hashed, and every git dependency fetched,
   * whenever the manifest or the lock changes. What validation hands format.
   */
  packages: PackageInputs
  /** Whether a refresh is fetching from git right now (the explorer says so). */
  fetchingPackages: boolean
}

export interface PackagesActions {
  /**
   * Reads the packages the project's dependencies resolve to (format says
   * which, one round at a time: git dependencies fetched at the commits the
   * lock pins, then folders read), then validates.
   */
  refreshPackages(): Promise<void>
  /**
   * Resolves every git dependency afresh (each `rev`'s newest commit, as
   * `netherforge lock --update` does) and writes the lock that makes. False,
   * with a notice, when the dependencies don't resolve.
   */
  updatePackages(): Promise<boolean>
  /**
   * Copies the resource [id] of kind [kind] from the dependency [namespace]
   * into the project as [to]: its files as they are, then every JSON
   * document's references rewritten by format (`moveRefs`), so what named
   * the package's own things still does and what named the resource names
   * the copy. Then hot-reloads it. One transaction: undo removes the copy.
   * False, with a notice, if it failed.
   */
  copyFromPackage(namespace: string, kind: KindId, id: string, to: string): Promise<boolean>
  /**
   * Writes the package path [path] (`chess:modules/chess/init.lua`) of a package that's one of the
   * workspace's projects, edited in place, then re-reads the packages (so this project's lock
   * follows, and its server reloads) and brings every other project in the workspace that depends
   * on it up to date ([relockDependents]). False, with a notice, when it can't be written.
   */
  writePackageFile(path: string, text: string): Promise<boolean>
  /**
   * Gives every other project of the workspace that depends on [member] (by path) its new content
   * hash in their `netherforge.lock`: each write is a change in that project, so a server running
   * it reloads.
   */
  relockDependents(member: WorkspaceMember): Promise<void>
  /**
   * Copies the project template [id] (see `core/templates.ts`) into the
   * project as its own: every file where the template has it, and what the
   * template requires added to `netherforge.json`'s `requires` (saved). Then
   * hot-reloads what it wrote. One transaction: undo takes it all away. False,
   * with a notice, when the project already has a resource or file of the
   * same name, or it couldn't be written.
   */
  applyTemplate(id: string): Promise<boolean>
  /**
   * Adds the dependency [namespace] on [source] to `netherforge.json` (saved),
   * which reads its package. False, with a notice, when the project already
   * depends on that namespace or the manifest doesn't read.
   */
  addDependency(namespace: string, source: DependencySource): Promise<boolean>
  /** What a validation found about the packages: the lock to keep, the packages to mark read-only. */
  packagesValidated(outline: ProjectOutline): void
}

/** A dependency's source in `netherforge.json`: a folder relative to the project, or a git repository at a rev. */
export type DependencySource = { path: string } | { git: string; rev?: string }

/** [into] with what [extra] requires too: a capability either asks for, hosts and plugins in either (in order). */
export function mergeRequires(
  into: NonNullable<ProjectManifest['requires']>,
  extra: NonNullable<ProjectManifest['requires']>,
) {
  if (extra.moderation) into.moderation = true
  if (extra.db) into.db = true
  if (extra.web) into.web = true
  if (extra.bots) into.bots = true
  if (extra.wasm) into.wasm = true
  for (const key of ['http', 'plugins'] as const) {
    const more = (extra[key] ?? []).filter((it) => !into[key]?.includes(it))
    if (more.length) into[key] = [...(into[key] ?? []), ...more]
  }
}

export const PACKAGES_INITIAL: PackagesState = { packages: NO_PACKAGES, fetchingPackages: false }

/**
 * Where a package came from, in a few words: its folder (`../library`), or
 * a git repository at the commit the lock pins (`https://…/economy.git at
 * v1.2.0 (3f2a91c)`).
 */
export function describeOrigin(origin: PackageSource): string {
  switch (origin.type) {
    case 'path':
      return origin.path
    case 'git':
      return `${origin.url} at ${origin.rev ?? 'its default branch'} (${origin.commit.slice(0, 7)})`
    case 'registry':
      return origin.url
  }
}

/** [path] with `.` and `..` resolved (`/w/servers/chess/../../packages/chess` → `/w/packages/chess`). */
export function normalizeFolder(path: string): string {
  const absolute = path.startsWith('/')
  const parts: string[] = []
  for (const part of path.replaceAll('\\', '/').split('/')) {
    if (part === '..') parts.pop()
    else if (part && part !== '.') parts.push(part)
  }
  const joined = parts.join('/')
  // A Windows drive's path (`C:/w/…`) has no leading slash to put back.
  return absolute ? `/${joined}` : joined
}

/**
 * The workspace project a package [location] of [project] is (`../../packages/chess`, from
 * `servers/chess`), when the project is in a workspace and the location is a folder of it: a
 * package the project may edit in place. Null for a git package or a folder outside it.
 */
export function workspaceMemberAt(
  project: ProjectInfo | null,
  location: string,
): WorkspaceMember | null {
  return project?.workspace ? memberFrom(project.workspace, project.root, location) : null
}

/** The member of [workspace] a package [location] of the project at [root] is, as [workspaceMemberAt]. */
export function memberFrom(
  workspace: WorkspaceInfo,
  root: string,
  location: string,
): WorkspaceMember | null {
  if (location.startsWith('git:')) return null
  const folder = normalizeFolder(`${root}/${location}`)
  return workspace.members.find((it) => normalizeFolder(it.root) === folder) ?? null
}

/** A package path's package (`library:items/gem` → `library`) and its path there; null for a project path. */
export function splitPackagePath(path: string): [string | null, string] {
  const colon = path.indexOf(':')
  return colon < 0 ? [null, path] : [path.slice(0, colon), path.slice(colon + 1)]
}

/** The manifest's text as JSON, or null when there's none or it doesn't parse. */
function manifestJson(text: string | undefined): unknown {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

/**
 * The packages the project depends on directly (its `netherforge.json`'s
 * `dependencies`, as it is now): whose exports its files and scripts may
 * name. Empty when the manifest doesn't parse.
 */
export function directDependencies(s: Pick<Workspace, 'docs' | 'diskTexts'>): string[] {
  const doc = s.docs[MANIFEST_FILE]
  const manifest =
    doc && !doc.deleted && isModel(doc)
      ? doc.history.present
      : manifestJson(doc && !doc.deleted ? doc.text : s.diskTexts[MANIFEST_FILE])
  const dependencies = (manifest as { dependencies?: unknown } | null)?.dependencies
  return typeof dependencies === 'object' && dependencies !== null ? Object.keys(dependencies) : []
}

/**
 * What files and scripts of the project may name of [kind] in the packages
 * it depends on directly ([direct]): each one's exports of that kind that
 * it has, as `ns:id`, sorted.
 */
export function exportedIds(
  outline: Pick<ProjectOutline, 'packages'> | null,
  direct: string[],
  kind: KindId,
): string[] {
  return direct
    .flatMap((namespace) => {
      const pkg = outline?.packages?.[namespace]
      const ids = pkg?.resources[kind] ?? []
      return (pkg?.exports[KINDS[kind].folder] ?? [])
        .filter((id) => ids.includes(id))
        .map((id) => `${namespace}:${id}`)
    })
    .sort()
}

/**
 * A package's JSON file as it was read, by package path
 * (`library:items/gem/item.json`); undefined for a project path, a package
 * the outline doesn't have, or a file that isn't there or isn't JSON.
 */
export function packageText(
  s: { packages: PackageInputs; outline: Pick<ProjectOutline, 'packages'> | null },
  path: string,
): string | undefined {
  const [pkg, local] = splitPackagePath(path)
  if (pkg === null) return undefined
  const location = s.outline?.packages?.[pkg]?.location
  return (
    (location === undefined ? undefined : s.packages.folders[location]?.files[local]) ?? undefined
  )
}

/**
 * The URL a preview loads a file from: a project file's, or a package's by
 * its package path (`library:resource_packs/gems/textures/gem.png`), served from the
 * package's folder ([Backend.packageFileUrl]). Empty for a package the
 * outline doesn't have.
 */
export function fileUrl(
  backend: Pick<Backend, 'projectFileUrl' | 'packageFileUrl'>,
  outline: Pick<ProjectOutline, 'packages'> | null,
  path: string,
  stamp?: number,
): string {
  const [pkg, local] = splitPackagePath(path)
  if (pkg === null) return backend.projectFileUrl(path, stamp)
  const location = outline?.packages?.[pkg]?.location
  return location === undefined ? '' : backend.packageFileUrl(location, local, stamp)
}

/** Each package folder's file list by package path, per namespace: worked out once per read of the folder. */
const listedFiles = new WeakMap<PackageFolder, Map<string, string[]>>()

/**
 * The files of package [pkg] by package path (`library:items/gem/item.json`), sorted, as the packages slice read
 * them; the project's own ([Workspace.files]) for null. What lists a resource's files lists a package's from here,
 * so a dependency's resource opened read-only shows its own files and not the project's. Empty for a package the
 * outline doesn't have.
 */
export function filesIn(
  s: Pick<Workspace, 'files' | 'packages'> & { outline: Pick<ProjectOutline, 'packages'> | null },
  pkg: string | null,
): string[] {
  if (pkg === null) return s.files
  const location = s.outline?.packages?.[pkg]?.location
  const folder = location === undefined ? null : s.packages.folders[location]
  if (!folder) return NO_FILES
  let byNamespace = listedFiles.get(folder)
  if (!byNamespace) listedFiles.set(folder, (byNamespace = new Map()))
  let listed = byNamespace.get(pkg)
  if (!listed)
    byNamespace.set(
      pkg,
      (listed = Object.keys(folder.files)
        .sort()
        .map((it) => `${pkg}:${it}`)),
    )
  return listed
}

const NO_FILES: string[] = []

/** A read-only mark for a whole package: `library:` (see `readOnlyReason`). */
export const isPackageMark = (path: string) => path.endsWith(':')

export function packagesSlice({ backend, get, set }: SliceArgs): PackagesState & PackagesActions {
  /**
   * A package folder as format reads it: its JSON and Lua as text (as the
   * project's are read; the Lua also gives LuaLS's stubs of what it
   * exports), the rest null, and its content hash
   * from the backend's digests. Null when there's no package there.
   */
  const readPackage = async (location: string): Promise<PackageFolder | null> => {
    let listed
    try {
      listed = await backend.packageFiles(location)
    } catch {
      return null
    }
    const files: Record<string, string | null> = {}
    for (const { path } of listed.files) {
      files[path] =
        readsText(path) && isPackageFile(path)
          ? await backend.packageReadText(location, path).catch(() => null)
          : null
    }
    const digests = Object.fromEntries(listed.files.map((it) => [it.path, it.sha256]))
    return { files, hash: await packageHash(digests) }
  }

  /**
   * Everything resolving [files]' dependencies needs, as format asks for it
   * round by round: git dependencies fetched into the package cache (a
   * failure is format's to report, with the backend's words), then folders
   * read. Null once [stale] says a newer refresh has begun.
   */
  const resolvePackages = async (
    files: Record<string, string | null>,
    stale: () => boolean,
  ): Promise<PackageInputs | null> => {
    const folders: PackageInputs['folders'] = {}
    const git: PackageInputs['git'] = {}
    try {
      for (;;) {
        const needed = packagesNeeded(files, { folders, git })
        if (needed.locations.length === 0 && needed.git.length === 0) break
        if (needed.git.length > 0) set({ fetchingPackages: true })
        for (const request of needed.git) {
          try {
            git[request.key] = {
              commit: await backend.packageFetchGit(
                request.url,
                request.rev ?? null,
                request.commit ?? null,
              ),
            }
          } catch (error) {
            git[request.key] = { error: errorText(error) }
          }
        }
        for (const location of needed.locations) folders[location] = await readPackage(location)
        if (stale()) return null
      }
    } finally {
      if (get().fetchingPackages) set({ fetchingPackages: false })
    }
    return { folders, git }
  }

  /** Bumped by every [refreshPackages], so a slower earlier one doesn't land over it. */
  let generation = 0

  /** Whether the manifest, as it is now, names any dependency. */
  const declaresDependencies = (): boolean => {
    const text = projectInput(get())[MANIFEST_FILE]
    try {
      const manifest = text ? (JSON.parse(text) as { dependencies?: unknown }) : null
      const dependencies = manifest?.dependencies
      return typeof dependencies === 'object' && dependencies !== null
        ? Object.keys(dependencies).length > 0
        : false
    } catch {
      // A manifest that doesn't parse says nothing; leave the lock be.
      return true
    }
  }

  /**
   * Keeps `netherforge.lock` what the packages resolve to: written when
   * format's lock differs from the file, deleted when the project no longer
   * declares dependencies. Never over the lock open with unsaved edits.
   */
  let writingLock = false
  const syncLock = async (outline: ProjectOutline) => {
    const { project, diskTexts, docs, files } = get()
    if (!project || writingLock || docs[LOCK_FILE]?.dirty) return
    const disk = diskTexts[LOCK_FILE]
    writingLock = true
    try {
      if (outline.lock != null && outline.lock !== disk) {
        await backend.writeText(LOCK_FILE, outline.lock)
        set({
          diskTexts: { ...get().diskTexts, [LOCK_FILE]: outline.lock },
          fileStamps: { ...get().fileStamps, [LOCK_FILE]: nextNonce() },
        })
      } else if (outline.lock == null && files.includes(LOCK_FILE) && !declaresDependencies()) {
        await backend.deletePath(LOCK_FILE)
        const diskTexts = { ...get().diskTexts }
        delete diskTexts[LOCK_FILE]
        set({ diskTexts })
      } else {
        return
      }
      await get().refreshFiles()
      // The plugin restarts everything for a new lock: a package changed.
      get().hotReload([LOCK_FILE])
      // A git dependency is now asked for at the commit the lock pins.
      await get().refreshPackages()
    } catch (error) {
      get().notify('error', `Couldn't update ${LOCK_FILE}: ${errorText(error)}`)
    } finally {
      writingLock = false
    }
  }

  /**
   * Every package the outline has is read-only as a whole (`library:`), and one no longer depended
   * on isn't; nor is one that's a project of the workspace, which is edited in place.
   */
  const markPackages = (outline: ProjectOutline) => {
    const marked = Object.keys(get().readOnly).filter(isPackageMark)
    const project = get().project
    const wanted = Object.entries(outline.packages ?? {})
      .filter(([, it]) => !workspaceMemberAt(project, it.location))
      .map(([namespace]) => `${namespace}:`)
    if (marked.length === wanted.length && wanted.every((it) => marked.includes(it))) return
    const readOnly = Object.fromEntries(
      Object.entries(get().readOnly).filter(([path]) => !isPackageMark(path)),
    )
    for (const mark of wanted) {
      readOnly[mark] =
        `it's in the package "${mark.slice(0, -1)}", which this project uses as it is (copy it into the project from the explorer's Dependencies to change it)`
    }
    set({ readOnly })
  }

  return {
    ...PACKAGES_INITIAL,

    async refreshPackages() {
      if (!get().project) return
      const mine = ++generation
      const packages = await resolvePackages(projectInput(get()), () => mine !== generation)
      if (packages === null) return
      set({ packages })
      get().validateSoon()
    },

    async updatePackages() {
      if (!get().project) return false
      const files = { ...projectInput(get()) }
      delete files[LOCK_FILE]
      const packages = await resolvePackages(files, () => false)
      if (packages === null || !get().project) return false
      const outline = loadProject(files, null, packages)
      const broken = outline.problems.filter(
        (it) => it.severity === 'error' && it.code?.startsWith('package.'),
      )
      if (broken.length > 0 || outline.lock == null) {
        get().notify(
          'error',
          `Couldn't update the packages: ${broken[0]?.message ?? "one couldn't be hashed"}`,
        )
        return false
      }
      if (outline.lock !== get().diskTexts[LOCK_FILE]) {
        try {
          await backend.writeText(LOCK_FILE, outline.lock)
        } catch (error) {
          get().notify('error', `Couldn't update ${LOCK_FILE}: ${errorText(error)}`)
          return false
        }
        set({
          diskTexts: { ...get().diskTexts, [LOCK_FILE]: outline.lock },
          fileStamps: { ...get().fileStamps, [LOCK_FILE]: nextNonce() },
        })
        await get().refreshFiles()
        get().hotReload([LOCK_FILE])
      }
      await get().refreshPackages()
      return true
    },

    async copyFromPackage(namespace, kind, id, to) {
      const pkg = get().outline?.packages?.[namespace]
      const location = pkg?.location
      const home = namespaceOf(get())
      if (location === undefined || home === null) return false
      const target = locationOf(kind, to)
      return get().transact(`Copy ${namespace}:${id} into the project as ${to}`, async () => {
        await get().touch(target)
        try {
          // From wherever the package keeps it (its group folders) into the kind's own folder.
          await backend.packageCopy(
            location,
            locationOf(kind, id, pkg?.groups?.[kind]?.[id]),
            target,
          )
        } catch (error) {
          get().notify('error', `Couldn't copy ${namespace}:${id}: ${errorText(error)}`)
          return false
        }
        await get().refreshFiles()
        const written = get().files.filter((it) => isUnder(it, target))
        const texts: Record<string, string> = {}
        for (const path of written) {
          if (!readsText(path)) continue
          try {
            const text = await backend.readText(path)
            const moved = documentKindOf(path)
              ? moveRefs(path, text, namespace, home, { type: 'resource', kind, id }, to)
              : null
            if (moved !== null && moved !== text) await backend.writeText(path, moved)
            texts[path] = moved ?? text
          } catch (error) {
            get().notify('error', `Couldn't update ${path}: ${errorText(error)}`)
          }
        }
        set({ diskTexts: { ...get().diskTexts, ...texts } })
        get().validateSoon()
        get().hotReload(written)
        return true
      })
    },

    async applyTemplate(id) {
      const template = templateById(id)
      if (!get().project || !template) return false
      const paths = Object.keys(template.files)
      const taken = paths.flatMap((path) => {
        const found = resourceOf(path)
        if (!found) return get().files.includes(path) ? [path] : []
        // By id, wherever the project keeps it (a group folder too).
        const ids = get().outline?.resources[found.kind] ?? []
        return ids.includes(found.id) ? [locationOf(found.kind, found.id)] : []
      })
      if (taken.length) {
        const names = [...new Set(taken)].join(', ')
        get().notify('error', `Couldn't add ${template.title}: the project already has ${names}.`)
        return false
      }
      if (get().refuseReadOnly(MANIFEST_FILE)) return false
      const manifest = await get().loadDoc(MANIFEST_FILE)
      if (!manifest || !isModel(manifest)) {
        get().notify('error', `Fix ${MANIFEST_FILE} first: it doesn't read as a project.`)
        return false
      }
      // Each resource's folder (or file), and the other files as they are.
      const roots = [
        ...new Set(
          paths.map((path) => {
            const found = resourceOf(path)
            return found ? locationOf(found.kind, found.id) : path
          }),
        ),
      ]
      return get().transact(`Add the ${template.title} template`, async () => {
        await get().touch(...roots, MANIFEST_FILE)
        try {
          for (const [path, text] of Object.entries(template.files)) {
            await backend.writeText(path, text)
          }
        } catch (error) {
          get().notify('error', `Couldn't add ${template.title}: ${errorText(error)}`)
          return false
        }
        await get().refreshFiles()
        get().edit<ProjectManifest>(MANIFEST_FILE, (draft) => {
          const requires = { ...draft.requires }
          mergeRequires(requires, template.requires)
          if (Object.keys(requires).length) draft.requires = requires
        })
        // Saved here, since nobody has the settings page open to do it.
        const saved = await get().save(MANIFEST_FILE)
        get().validateSoon()
        get().hotReload(paths)
        return saved
      })
    },

    async addDependency(namespace, source) {
      if (!get().project) return false
      if (directDependencies(get()).includes(namespace)) {
        get().notify('info', `The project already depends on ${namespace}.`)
        return false
      }
      if (get().refuseReadOnly(MANIFEST_FILE)) return false
      const manifest = await get().loadDoc(MANIFEST_FILE)
      if (!manifest || !isModel(manifest)) {
        get().notify('error', `Fix ${MANIFEST_FILE} first: it doesn't read as a project.`)
        return false
      }
      get().edit<ProjectManifest>(MANIFEST_FILE, (draft) => {
        const next = { ...draft.dependencies, [namespace]: source }
        draft.dependencies = Object.fromEntries(
          Object.entries(next).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
        )
      })
      return get().save(MANIFEST_FILE)
    },

    async writePackageFile(path, text) {
      const [namespace, inner] = splitPackagePath(path)
      const location = namespace ? get().outline?.packages?.[namespace]?.location : undefined
      const member = location ? workspaceMemberAt(get().project, location) : null
      if (!member) {
        get().notify(
          'error',
          `Couldn't save ${path}: its package isn't one of the workspace's projects`,
        )
        return false
      }
      try {
        await backend.workspaceMemberWriteText(member.path, inner, text)
      } catch (error) {
        get().notify('error', `Couldn't save ${path}: ${errorText(error)}`)
        return false
      }
      // Its hash changed: this project's lock follows (and its server reloads), and so do the others'.
      void get().refreshPackages()
      void get().relockDependents(member)
      return true
    },

    async relockDependents(member) {
      const project = get().project
      const workspace = project?.workspace
      if (!project || !workspace) return
      let hash: string
      try {
        const listed = await backend.workspaceMemberFiles(member.path)
        hash = await packageHash(Object.fromEntries(listed.files.map((it) => [it.path, it.sha256])))
      } catch {
        return
      }
      for (const other of workspace.members) {
        // This project's own lock is syncLock's, from what validation says.
        if (other.root === project.root || other.root === member.root) continue
        let text: string
        try {
          text = await backend.workspaceMemberReadText(other.path, LOCK_FILE)
        } catch {
          continue
        }
        let lock: {
          packages?: Record<string, { source?: { type?: string; path?: string }; hash?: string }>
        }
        try {
          lock = JSON.parse(text)
        } catch {
          continue
        }
        let changed = false
        for (const entry of Object.values(lock.packages ?? {})) {
          const source = entry.source
          if (source?.type !== 'path' || !source.path) continue
          if (normalizeFolder(`${other.root}/${source.path}`) !== normalizeFolder(member.root))
            continue
          if (entry.hash !== hash) {
            entry.hash = hash
            changed = true
          }
        }
        if (!changed) continue
        const written = canonicalize('lock', LOCK_FILE, JSON.stringify(lock))
        if (written.text) {
          await backend
            .workspaceMemberWriteText(other.path, LOCK_FILE, written.text)
            .catch(() => {})
        }
      }
    },

    packagesValidated(outline) {
      void syncLock(outline)
      markPackages(outline)
    },
  }
}
