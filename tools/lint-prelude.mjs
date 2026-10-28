#!/usr/bin/env node
/**
 * Type-checks the Lua we ship with the pinned lua-language-server, the same binary and version
 * the editor runs (`tools/luals.mjs` fetches it, cached):
 *
 *  - the plugin's (the prelude's modules, the caps, the generated bindings and schema):
 *    `--check` over `apps/plugin/runtime/src`, with the prelude's modules resolvable by `require`
 *    as they are at runtime (`src/luals/input.lua` says what the Kotlin host hands the entry);
 *  - format's (`packages/format/src/commonMain/lua`, the terrain scripts' glue): `--check` over
 *    `packages/format/src` (`src/luals/terrain-host.lua` says what the Kotlin host hands it).
 *
 * Prints one line per diagnostic, at every level down to hints (an unused local is one), and
 * exits non-zero when there are any. Run by `pnpm lint`.
 */
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { binaryIn, fetchLuals } from './luals.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const src = path.join(root, 'apps/plugin/runtime/src')
const lua = 'dev/netherforge/plugin/lua'

/** What every check runs with. */
const common = {
  'runtime.version': 'Lua 5.4',
  'workspace.checkThirdParty': false,
  // Everything but `strong` (`no-unknown`, which wants every value's type written out).
  'diagnostics.groupFileStatus': {
    ambiguity: 'Any',
    await: 'Any',
    duplicate: 'Any',
    global: 'Any',
    luadoc: 'Any',
    redefined: 'Any',
    strict: 'Any',
    'type-check': 'Any',
    unbalanced: 'Any',
    unused: 'Any',
  },
}

/** Each folder checked, and what it adds to [common]. */
const targets = [
  {
    dir: src,
    settings: {
      // `require("std")` finds the prelude's module of that name.
      'runtime.path': [
        path.join(src, 'main/resources', lua, 'prelude/?.lua'),
        path.join(src, 'generated/lua', lua, 'prelude/?.lua'),
        path.join(src, 'luals/?.lua'),
      ].map((it) => it.split(path.sep).join('/')),
      // The prelude resumes and yields coroutines itself: `await` is how scripts are held to
      // waiting only in tasks, which the generated stubs (not these modules) mark.
      'diagnostics.disable': ['await-in-sync'],
    },
  },
  { dir: path.join(root, 'packages/format/src'), settings: {} },
]

const work = mkdtempSync(path.join(tmpdir(), 'netherforge-lint-prelude-'))
try {
  const target = process.env.LUA_LANGUAGE_SERVER
  const binary = target ?? binaryIn(await fetchLuals())
  if (!existsSync(binary)) throw new Error(`no lua-language-server at ${binary}`)
  const lines = targets.flatMap((it, index) => check(binary, it, path.join(work, String(index))))
  if (lines.length) {
    console.error(lines.sort().join('\n'))
    console.error(`\n${lines.length} problem(s) in our Lua`)
    process.exitCode = 1
  } else {
    console.log("lua-language-server: the plugin's and format's Lua type-check")
  }
} catch (error) {
  console.error(`lint-prelude: ${error.message}`)
  process.exitCode = 1
} finally {
  rmSync(work, { recursive: true, force: true })
}

/** One line per diagnostic lua-language-server finds in [target]'s folder. */
function check(binary, target, dir) {
  mkdirSync(dir)
  const config = path.join(dir, 'luarc.json')
  const report = path.join(dir, 'report.json')
  writeFileSync(config, JSON.stringify({ ...common, ...target.settings }))
  const result = spawnSync(
    binary,
    [
      `--check=${target.dir}`,
      `--configpath=${config}`,
      '--checklevel=Hint',
      '--check_format=json',
      `--check_out_path=${report}`,
      `--logpath=${path.join(dir, 'log')}`,
    ],
    { encoding: 'utf8', timeout: 300_000 },
  )
  if (!existsSync(report))
    throw new Error(`lua-language-server wrote no report:\n${result.stdout}${result.stderr}`)
  const text = readFileSync(report, 'utf8').trim()
  /** @type {Record<string, { code: string, message: string, range: { start: { line: number } } }[]>} */
  const found = text ? JSON.parse(text) : {}
  return Object.entries(found).flatMap(([uri, diagnostics]) =>
    diagnostics.map(
      (d) =>
        `${path.relative(root, fileURLToPath(uri))}:${d.range.start.line + 1} [${d.code}] ${d.message.split('\n')[0]}`,
    ),
  )
}
