/**
 * The workspace's other projects, checked as the open one is: each member's files read through the
 * backend's member commands, its packages resolved (a path dependency on another member read from
 * there, a git one from the package cache), and validated once in the validation worker. What
 * Problems lists under "Other projects in the workspace".
 */
import {
  isPackageFile,
  MANIFEST_FILE,
  packageHash,
  packagesNeeded,
  type PackageFolder,
  type PackageInputs,
  type Problem,
} from '@/core/format'
import type { Backend, WorkspaceInfo, WorkspaceMember } from '@/core/backend/types'
import type { CheckRequest } from '@/core/validation/requests'
import { readsText } from '@/core/paths'
import { memberFrom } from './packages'
import { errorText } from './slice'

/** A member's files as format reads them: text for what it reads, null for the rest. */
async function memberFiles(
  backend: Backend,
  member: WorkspaceMember,
  keep: (path: string) => boolean,
): Promise<{ files: Record<string, string | null>; digests: Record<string, string> }> {
  const listed = await backend.workspaceMemberFiles(member.path)
  const files: Record<string, string | null> = {}
  for (const { path } of listed.files) {
    files[path] =
      readsText(path) && keep(path)
        ? await backend.workspaceMemberReadText(member.path, path).catch(() => null)
        : null
  }
  return { files, digests: Object.fromEntries(listed.files.map((it) => [it.path, it.sha256])) }
}

/**
 * [member]'s packages, as format asks for them round by round: another member at a path
 * dependency's location, a git checkout from the package cache, null for a folder outside the
 * workspace (the editor reads only the open project's and the workspace's).
 */
async function memberPackages(
  backend: Backend,
  workspace: WorkspaceInfo,
  member: WorkspaceMember,
  files: Record<string, string | null>,
): Promise<PackageInputs> {
  const folders: PackageInputs['folders'] = {}
  const git: PackageInputs['git'] = {}
  const readFolder = async (location: string): Promise<PackageFolder | null> => {
    try {
      if (location.startsWith('git:')) {
        const listed = await backend.packageFiles(location)
        const read: Record<string, string | null> = {}
        for (const { path } of listed.files) {
          read[path] =
            readsText(path) && isPackageFile(path)
              ? await backend.packageReadText(location, path).catch(() => null)
              : null
        }
        const digests = Object.fromEntries(listed.files.map((it) => [it.path, it.sha256]))
        return { files: read, hash: await packageHash(digests) }
      }
      const target = memberFrom(workspace, member.root, location)
      if (!target) return null
      const read = await memberFiles(backend, target, isPackageFile)
      return { files: read.files, hash: await packageHash(read.digests) }
    } catch {
      return null
    }
  }
  for (;;) {
    const needed = packagesNeeded(files, { folders, git })
    if (needed.locations.length === 0 && needed.git.length === 0) break
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
    for (const location of needed.locations) folders[location] = await readFolder(location)
  }
  return { folders, git }
}

/** Everything checking [member] needs: its files, the game data for its Minecraft version, its packages. */
export async function memberCheckRequest(
  backend: Backend,
  workspace: WorkspaceInfo,
  member: WorkspaceMember,
): Promise<CheckRequest> {
  const { files } = await memberFiles(backend, member, () => true)
  let minecraft: string | null = null
  try {
    minecraft =
      (JSON.parse(files[MANIFEST_FILE] ?? '{}') as { minecraft?: string }).minecraft ?? null
  } catch {
    // The manifest's own problem says so.
  }
  const gameData = minecraft ? await backend.gameData(minecraft).catch(() => null) : null
  const packages = await memberPackages(backend, workspace, member, files)
  return { files, gameData, packages }
}

/** One other member's problems, or why it couldn't be checked. */
export interface MemberProblems {
  member: WorkspaceMember
  problems: Problem[]
  error: string | null
}
