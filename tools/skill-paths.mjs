#!/usr/bin/env node
// Fails if an editor skill (.claude/skills/editor-*/SKILL.md) names a file or
// folder of the editor that isn't there, so the guide can't drift from the code
// as files move.
//
// The editor skills write paths from apps/editor/src (`core/paths.ts`,
// `editors/terrain/inspector/`) or apps/editor (`e2e/terrain.spec.ts`), in
// backticks. A path names a file with or without its extension (`ui/Tree` is
// `ui/Tree.tsx`); `#anchor`s are dropped. Anything in backticks that isn't under
// one of those folders is left alone.
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const editor = path.join(root, 'apps/editor')
const skills = path.join(root, '.claude/skills')

/** Each path prefix, and the folder it's written from. */
const BASES = [
  [/^(core|ui|state|minecraft|editors|workbench|app|testing)\//, path.join(editor, 'src')],
  [/^e2e\//, editor],
]
const EXTENSIONS = ['', '.ts', '.tsx', '.css', '.module.css']
/** What looks like a path and isn't one. */
const NOT_PATHS = new Set([
  // A glyph's name in a resource pack, as `<glyph:ui/coin>` writes it.
  'ui/coin',
])

const problems = []
for (const dir of readdirSync(skills).filter((it) => it.startsWith('editor-'))) {
  const file = path.join(skills, dir, 'SKILL.md')
  if (!existsSync(file)) continue
  readFileSync(file, 'utf8')
    .split('\n')
    .forEach((line, index) => {
      for (const [, token] of line.matchAll(/`([^`\s]+)`/g)) {
        const named = token.split('#')[0].replace(/[,.:;)]+$/, '')
        if (NOT_PATHS.has(named) || /[<>{}*]/.test(named)) continue
        const base = BASES.find(([prefix]) => prefix.test(named))?.[1]
        if (!base) continue
        if (EXTENSIONS.some((ext) => existsSync(path.join(base, named + ext)))) continue
        problems.push(`.claude/skills/${dir}/SKILL.md:${index + 1}: no ${named}`)
      }
    })
}

if (problems.length > 0) {
  console.error(problems.join('\n'))
  console.error(`\n${problems.length} path(s) the editor skills name aren't in apps/editor.`)
  process.exit(1)
}
