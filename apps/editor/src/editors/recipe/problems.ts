/**
 * Which of a file's problems are about one field, so the recipe editor can
 * show each next to its slot or field. Paths compare the way `data-path`s
 * are written (`key.R`, `ingredients[2]`, `result.kind`).
 */
import type { Problem } from '@/core/format'
import { parseJsonPath } from '@/core/paths'
import { fieldPath } from '@/editors/shared/focus'

/** A problem's path as a field path; `''` for one about the whole file. */
export const problemField = (problem: Problem) =>
  problem.path ? fieldPath(parseJsonPath(problem.path)) : ''

/** The problems at [field] or anywhere inside it. */
export function problemsUnder(problems: Problem[], field: string): Problem[] {
  return problems.filter((problem) => {
    const at = problemField(problem)
    return at === field || at.startsWith(`${field}.`) || at.startsWith(`${field}[`)
  })
}
