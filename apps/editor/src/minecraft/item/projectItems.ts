/**
 * Project items and recipes as the editors read them: each file as it is now
 * (an open document's unsaved edits included, else disk), parsed once per
 * text. Reading only; every write still goes through the workspace and
 * format.
 */
import { useMemo } from 'react'
import {
  qualifyRefs,
  resolveReference,
  type ItemFile,
  type KindId,
  type RecipeFile,
} from '@/core/format'
import { groupOf, mainFileOf, resourceOf, resourceIdsOf } from '@/core/paths'
import { isModel } from '@/core/store/documents'
import { directDependencies, exportedIds, packageText } from '@/core/store/packages'
import type { Workspace } from '@/core/store/workspace'
import { useWorkspace } from '@/state/providers'
import { useHomeNamespace, useReferenceNamespace } from '@/state/useResourcePacks'

/** An open document's text per model: models are immutable, and selectors run on every store change. */
const modelTexts = new WeakMap<object, string>()

/** A project JSON file's current text: its open document's (model or text), else disk's. */
export function currentText(
  s: Pick<Workspace, 'docs' | 'diskTexts'>,
  path: string,
): string | undefined {
  const doc = s.docs[path]
  if (!doc || doc.deleted) return s.diskTexts[path]
  if (!isModel(doc)) return doc.text
  const model = doc.history.present
  if (typeof model !== 'object' || model === null) return JSON.stringify(model)
  let text = modelTexts.get(model)
  if (text === undefined) {
    text = JSON.stringify(model)
    modelTexts.set(model, text)
  }
  return text
}

const parsed = new Map<string, unknown>()
const PARSED_LIMIT = 500

/** [text] parsed, or null when it isn't JSON; the same text is parsed once. */
export function parseCached<T>(text: string | undefined): T | null {
  if (text === undefined) return null
  if (parsed.has(text)) return parsed.get(text) as T | null
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    value = null
  }
  if (parsed.size >= PARSED_LIMIT) parsed.clear()
  parsed.set(text, value)
  return value as T | null
}

/**
 * Where the item a stack names (`ruby`, `library:gem`, written in namespace
 * [namespace]) is defined: its file as a project path or a package path, and
 * the namespace that file is written in. [groupOf] says which group folders
 * the project (or a package, by namespace) keeps an item in. Null when it
 * isn't shaped like one.
 */
export function itemFileOf(
  ref: string,
  namespace: string,
  home: string,
  groupOf: (owner: string, id: string) => string = () => '',
): { path: string; namespace: string } | null {
  const key = resolveReference('item', ref, namespace)
  if (!key) return null
  const colon = key.indexOf(':')
  const owner = key.slice(0, colon)
  const id = key.slice(colon + 1)
  const file = mainFileOf('item', id, groupOf(owner, id))
  return { path: owner === home ? file : `${owner}:${file}`, namespace: owner }
}

/** Which group folders the item [id] of [owner] (the project's namespace, [home], or a package's) is kept in. */
const itemGroup = (
  s: Pick<Workspace, 'files' | 'outline'>,
  home: string,
  owner: string,
  id: string,
): string =>
  owner === home
    ? groupOf(s.files, 'item', id)
    : (s.outline?.packages?.[owner]?.groups?.item?.[id] ?? '')

/** The text of the item file [ref] names, as it is now (the project's) or as it was read (a package's). */
function itemText(
  s: Pick<Workspace, 'docs' | 'diskTexts' | 'packages' | 'outline'>,
  file: { path: string } | null,
): string | undefined {
  if (!file) return undefined
  return file.path.includes(':') ? packageText(s, file.path) : currentText(s, file.path)
}

/**
 * The definition of the project item a stack names, as it is now, or null
 * (no item, no such item, or it doesn't parse). [ref] is written in the
 * namespace of the document on show; a package's item (`library:gem`) comes
 * with its references written in full (`library:gems/gem`), so its look
 * resolves the same beside the project's.
 */
export function useProjectItem(ref: string | undefined): ItemFile | null {
  const namespace = useReferenceNamespace()
  const home = useHomeNamespace()
  const key = ref ? resolveReference('item', ref, namespace) : null
  const colon = key?.indexOf(':') ?? -1
  // Only the item's group, so a change elsewhere in the file list or the outline renders nothing again.
  const group = useWorkspace((s) =>
    key ? itemGroup(s, home, key.slice(0, colon), key.slice(colon + 1)) : '',
  )
  const file = ref ? itemFileOf(ref, namespace, home, () => group) : null
  const path = file?.path
  const owner = file?.namespace
  const text = useWorkspace((s) => itemText(s, path === undefined ? null : { path }))
  const qualified = useMemo(() => {
    if (path === undefined || owner === undefined || text === undefined || owner === namespace)
      return text
    return qualifyRefs(path, text, owner) ?? text
  }, [path, owner, namespace, text])
  return parseCached<ItemFile>(qualified)
}

/**
 * Every [kind] a project file may name, sorted: the project's own by id, then
 * each one a package it depends on exports, as `ns:id`.
 */
export function useNameableIds(kind: KindId): string[] {
  const files = useWorkspace((s) => s.files)
  const packages = useWorkspace((s) => s.outline?.packages)
  const direct = useWorkspace((s) => directDependencies(s).join('\n'))
  return useMemo(
    () => [
      ...resourceIdsOf(files, kind),
      ...exportedIds({ packages }, direct.split('\n').filter(Boolean), kind),
    ],
    [files, packages, direct, kind],
  )
}

/** Every item a project file may name ([useNameableIds]). */
export const useProjectItemIds = (): string[] => useNameableIds('item')

export interface RecipeUse {
  id: string
  path: string
  makes: boolean
  uses: boolean
}

/** The recipes that make or take project item [id], by id. */
export function recipesUsing(
  s: Pick<Workspace, 'files' | 'docs' | 'diskTexts'>,
  id: string,
): RecipeUse[] {
  const out: RecipeUse[] = []
  for (const path of s.files) {
    const resource = resourceOf(path)
    if (resource?.kind !== 'recipe') continue
    const recipe = parseCached<RecipeFile>(currentText(s, path))
    if (!recipe || typeof recipe !== 'object') continue
    const use = recipeUse(recipe, id)
    if (use.makes || use.uses) out.push({ id: resource.id, path, ...use })
  }
  return out.sort((a, b) => a.id.localeCompare(b.id))
}

export function useRecipesUsing(id: string): RecipeUse[] {
  const files = useWorkspace((s) => s.files)
  const docs = useWorkspace((s) => s.docs)
  const diskTexts = useWorkspace((s) => s.diskTexts)
  return useMemo(() => recipesUsing({ files, docs, diskTexts }, id), [files, docs, diskTexts, id])
}

/** Whether a recipe makes project item [id] (its result) and whether it takes one (an ingredient). */
export function recipeUse(recipe: RecipeFile, id: string): { makes: boolean; uses: boolean } {
  const places = [
    ...Object.values(recipe.key ?? {}),
    ...(recipe.ingredients ?? []),
    recipe.ingredient,
    recipe.template,
    recipe.base,
    recipe.addition,
  ]
  return {
    makes: recipe.result?.item === id,
    uses: places.some((it) => it !== undefined && typeof it !== 'string' && it.item === id),
  }
}
