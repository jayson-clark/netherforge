import type { PackageInputs } from '@/core/format'
import type { FileContents } from '@/testing/backend/memory'
import { EXAMPLE_ROOT, LIBRARY_ROOT, exampleProject, libraryProject } from '@/testing/backend/seed'

export {
  EXAMPLE_ROOT,
  LIBRARY_ROOT,
  exampleFiles,
  exampleProject,
  libraryProject,
} from '@/testing/backend/seed'

/**
 * `examples/showcase`'s one dependency, `examples/library`, as `loadProject`
 * takes it: JSON as text, everything else null, no hash (so the lock's
 * hashes aren't compared).
 */
export const examplePackages: PackageInputs = {
  git: {},
  folders: {
    '../library': {
      files: Object.fromEntries(
        Object.entries(libraryProject).map(([path, contents]) => [
          path,
          path.endsWith('.json') && typeof contents === 'string' ? contents : null,
        ]),
      ),
      hash: null,
    },
  },
}

/**
 * `examples/showcase` at [root] and `examples/library` beside it (`../library`,
 * as the showcase's manifest says), as `MemoryBackend`'s `projects` takes them.
 */
export function exampleProjects(
  root: string = EXAMPLE_ROOT,
  project: Record<string, FileContents> = exampleProject,
): Record<string, Record<string, FileContents>> {
  const library =
    root === EXAMPLE_ROOT ? LIBRARY_ROOT : `${root.slice(0, root.lastIndexOf('/'))}/library`
  return { [root]: project, [library]: libraryProject }
}
