// @vitest-environment node
/**
 * Nothing in the editor lists resource kinds by hand: which kinds exist is
 * format's `KINDS`, and how each looks and behaves is its one entry in
 * `editors/registry.tsx`. A switch over kinds, an `a === 'x' || a === 'y'`,
 * an array or object of kind ids or a union of them anywhere else fails
 * here, and so does naming any kind at all in the shell (`core/`, `state/`,
 * `workbench/`, `app/`), which should only ever read the registry. An
 * editor naming its own kind (`mainFileOf('recipe', id)`) is fine.
 *
 * It reads the app's TypeScript with the type checker, so a kind id is found
 * by what it is (a string typed as a kind), not by what it looks like: `'item'`
 * as an icon name or a Lua type is not a kind.
 */
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { KINDS } from '@netherforge/format/constants'
import type * as TS from 'typescript'
import { describe, expect, it } from 'vitest'

const ts = createRequire(import.meta.url)('typescript') as typeof TS

const app = fileURLToPath(new URL('../..', import.meta.url))
const src = path.join(app, 'src')

/** The registry: the one place kinds are listed. */
const REGISTRY = 'editors/registry.tsx'
/** Folders that are the shell around the editors: they name no kind, only read the registry. */
const SHELL = ['core/', 'state/', 'workbench/', 'app/']
/** Files that name kinds anyway, and why. Keep this empty where possible. */
const EXCEPTIONS: Record<string, string> = {
  'core/luals/stubs.ts':
    "LuaLS's project names: a centity's nodes and animations and a dialog's buttons are parts of " +
    'those kinds that the Lua API names, read from their files',
}

const KIND_IDS = new Set<string>(Object.keys(KINDS))

interface Found {
  file: string
  line: number
  kinds: string[]
  why: string
}

/** Nodes a list of kinds is made of: one group per outermost such node. */
const LIST_PARTS = new Set([
  ts.SyntaxKind.BinaryExpression,
  ts.SyntaxKind.ParenthesizedExpression,
  ts.SyntaxKind.ConditionalExpression,
  ts.SyntaxKind.PrefixUnaryExpression,
  ts.SyntaxKind.CaseClause,
  ts.SyntaxKind.CaseBlock,
  ts.SyntaxKind.SwitchStatement,
  ts.SyntaxKind.ArrayLiteralExpression,
  ts.SyntaxKind.PropertyAssignment,
  ts.SyntaxKind.ObjectLiteralExpression,
])

function listOf(node: TS.Node): TS.Node {
  let at = node
  while (at.parent && LIST_PARTS.has(at.parent.kind)) at = at.parent
  return at
}

const EQUALITY = new Set([
  ts.SyntaxKind.EqualsEqualsEqualsToken,
  ts.SyntaxKind.ExclamationEqualsEqualsToken,
  ts.SyntaxKind.EqualsEqualsToken,
  ts.SyntaxKind.ExclamationEqualsToken,
])

/** Kind ids written in [files] where a kind is expected, by the list (or lone use) they're part of. */
function kindsIn(program: TS.Program, files: (file: TS.SourceFile) => boolean): Found[] {
  const checker = program.getTypeChecker()
  /** Whether [type] is kinds only (a `KindId`, an `EditedKind`, a union of some). */
  const isKindType = (type: TS.Type | undefined) => {
    if (!type) return false
    const members = (type.isUnion() ? type.types : [type]).filter(
      (it) => !(it.flags & (ts.TypeFlags.Undefined | ts.TypeFlags.Null)),
    )
    return (
      members.length >= 2 && members.every((it) => it.isStringLiteral() && KIND_IDS.has(it.value))
    )
  }
  /** The type a literal is compared with or used as. */
  const slotOf = (literal: TS.StringLiteralLike) => {
    const parent = literal.parent
    if (ts.isBinaryExpression(parent) && EQUALITY.has(parent.operatorToken.kind))
      return checker.getTypeAtLocation(parent.left === literal ? parent.right : parent.left)
    if (ts.isCaseClause(parent)) return checker.getTypeAtLocation(parent.parent.parent.expression)
    return checker.getContextualType(literal)
  }

  const found: Found[] = []
  for (const source of program.getSourceFiles()) {
    if (!files(source)) continue
    const groups = new Map<TS.Node, Set<string>>()
    const visit = (node: TS.Node) => {
      const kindLiteral =
        // A value: `'centity'` where a kind goes.
        (ts.isStringLiteralLike(node) &&
          KIND_IDS.has(node.text) &&
          !ts.isLiteralTypeNode(node.parent) &&
          isKindType(slotOf(node))) ||
        // A type: `'centity' | 'menu'`.
        (ts.isLiteralTypeNode(node) &&
          ts.isStringLiteral(node.literal) &&
          KIND_IDS.has(node.literal.text) &&
          ts.isUnionTypeNode(node.parent))
      if (kindLiteral) {
        const text = ts.isLiteralTypeNode(node)
          ? (node.literal as TS.StringLiteral).text
          : (node as TS.StringLiteral).text
        const list = ts.isLiteralTypeNode(node) ? node.parent : listOf(node)
        groups.set(list, (groups.get(list) ?? new Set()).add(text))
      }
      ts.forEachChild(node, visit)
    }
    visit(source)
    for (const [list, kinds] of groups) {
      found.push({
        file: path.relative(src, source.fileName).split(path.sep).join('/'),
        line: source.getLineAndCharacterOfPosition(list.getStart()).line + 1,
        kinds: [...kinds].sort(),
        why: kinds.size >= 2 ? 'lists kinds' : 'names a kind',
      })
    }
  }
  return found
}

/** What the rules above reject, as `file:line: why (kinds)`. */
function violations(found: Found[]): string[] {
  return found
    .filter((it) => it.file !== REGISTRY && !(it.file in EXCEPTIONS))
    .filter((it) => it.kinds.length >= 2 || SHELL.some((folder) => it.file.startsWith(folder)))
    .map((it) => `${it.file}:${it.line}: ${it.why} (${it.kinds.join(', ')})`)
}

function appProgram(extra?: { file: string; text: string }): TS.Program {
  const config = ts.getParsedCommandLineOfConfigFile(
    path.join(app, 'tsconfig.app.json'),
    {},
    {
      ...ts.sys,
      onUnRecoverableConfigFileDiagnostic: (diagnostic) => {
        throw new Error(ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'))
      },
    },
  )!
  const host = ts.createCompilerHost(config.options)
  if (extra) {
    const { readFile, fileExists } = host
    host.readFile = (file) => (file === extra.file ? extra.text : readFile.call(host, file))
    host.fileExists = (file) => file === extra.file || fileExists.call(host, file)
  }
  return ts.createProgram({
    rootNames: extra ? [...config.fileNames, extra.file] : config.fileNames,
    options: config.options,
    host,
  })
}

const isApp = (file: TS.SourceFile) =>
  file.fileName.startsWith(src.split(path.sep).join('/')) && !/\.test\.tsx?$/.test(file.fileName)

describe('resource kinds', () => {
  it('are listed only in the registry', () => {
    expect(violations(kindsIn(appProgram(), isApp))).toEqual([])
  }, 60_000)

  it('are caught when listed by hand', () => {
    const file = path.join(src, 'workbench', 'handList.ts').split(path.sep).join('/')
    const text = [
      "import type { KindId } from '@/core/format'",
      "import { mainFileOf } from '@/core/paths'",
      'export function icon(kind: KindId) {',
      '  switch (kind) {',
      "    case 'structure':",
      "    case 'map':",
      "      return 'globe'",
      '  }',
      "  return kind === 'module' || kind === 'resource_pack' ? 'code' : 'file'",
      '}',
      "export const PICKED: KindId[] = ['menu', 'dialog']",
      "export type Pair = 'item' | 'recipe'",
      "export const one = mainFileOf('centity', 'tower')",
      "export const notAKind = { icon: 'structure' as const, worker: 'module' }",
    ].join('\n')
    const found = kindsIn(appProgram({ file, text }), (it) => it.fileName === file)
    expect(violations(found)).toEqual([
      'workbench/handList.ts:4: lists kinds (map, structure)',
      'workbench/handList.ts:9: lists kinds (module, resource_pack)',
      'workbench/handList.ts:11: lists kinds (dialog, menu)',
      'workbench/handList.ts:12: lists kinds (item, recipe)',
      'workbench/handList.ts:13: names a kind (centity)',
    ])
  }, 60_000)
})
