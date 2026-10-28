/**
 * What a coding agent working on a project reads, for this version of
 * NetherForge: the docs site's guides, file-format spec and Lua API reference
 * (as their Markdown sources), an index, and the `netherforge` command. The
 * LuaLS stubs it's told to use are lua-language-server's, in
 * `.netherforge/luals/` (`luals.ts`), marked for what the project can't use.
 * The editor writes them into `.netherforge/` on open, and `netherforge setup`
 * whenever it's run, next to the schemas, so they always match the version
 * that will run the project. The project's AGENTS.md (format's
 * `Templates.newAgentFiles`) points there.
 */
import { AGENT_FILES } from '@netherforge/format/constants'
import { agentDocPaths, loadDocPages } from './bundled.ts'

const SECTIONS = [
  ['format/', 'File format', 'One page per kind of file. Read the page before changing that kind.'],
  ['reference/', 'Lua API reference', 'Every function, class and event a script can use.'],
  ['guide/', 'Guides', 'How the parts fit together, with examples.'],
] as const

/** The first `# Heading` of a Markdown page. */
function titleOf(text: string, fallback: string): string {
  return text.match(/^# (.+)$/m)?.[1]?.trim() ?? fallback
}

/** A short, stable fingerprint of the bundle, so an unchanged bundle isn't rewritten. */
function fingerprint(texts: string[]): string {
  let hash = 0x811c9dc5
  for (const text of texts) {
    for (let i = 0; i < text.length; i++) {
      hash ^= text.charCodeAt(i)
      hash = Math.imul(hash, 0x01000193)
    }
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

/** `.netherforge/docs/README.md`: what's there, with [cli] when the `netherforge` command is written beside it. */
export function agentReadme(
  version: string,
  pages: Record<string, string>,
  stamp: string,
  cli: boolean,
): string {
  const lines = [
    `# NetherForge ${version}: docs for agents`,
    '',
    `<!-- Written by NetherForge (the editor on open, or \`netherforge setup\`; bundle ${stamp}). Don't edit: it's rewritten. -->`,
    '',
    'Everything here matches the NetherForge version that runs this project. Prefer it to',
    'anything you remember about NetherForge or find online.',
    '',
    `- \`../luals/nf.lua\`: the whole Lua API as LuaLS stubs, typed and documented, in one file.`,
    "  Scripts may call only what it declares; what this project can't use (a newer Minecraft,",
    "  something `netherforge.json` doesn't allow) is marked `---@deprecated`. A centity, menu",
    '  and dialog each have one script, whose own handle is `this`; start a new one with the',
    "  editor's first line, `local this = this --[[@as Centity]]` (or `Menu`, or `Dialog`).",
    '- `../luals/surfaces/<kind>.lua`: what a centity, menu or dialog script sees beyond',
    '  `nf.lua` (its `this`, exactly).',
    '- `../schema/*.schema.json`: the JSON Schema of each project file kind.',
  ]
  if (cli) {
    lines.push(
      `- \`${AGENT_FILES.cli}\`: run with Node from the project root.`,
      `  - \`node ${AGENT_FILES.cli} check\` validates the whole project as the editor does:`,
      '    one `file:line: severity: message (at $.json.path)` per problem, exit 1 on errors.',
      `  - \`node ${AGENT_FILES.cli} format\` rewrites project JSON in canonical form.`,
      `  - \`node ${AGENT_FILES.cli} preview terrain/<id>.json --out map.png\` draws a terrain`,
      '    as the editor does (`--slice x --at 0` for the ground through a line).',
      `  - \`node ${AGENT_FILES.cli} render items/<id>/item.json --out icon.png\` draws an item, a block or a`,
      '    centity as the editor does; `gallery --out build/gallery` draws all of them, with a page.',
      `  - \`node ${AGENT_FILES.cli} generate\` runs netherforge.json's \`generate\` commands, then formats;`,
      `    a generator writes the project with \`import { project } from "${AGENT_FILES.cli}"\`.`,
    )
  }
  for (const [prefix, title, about] of SECTIONS) {
    const entries = Object.keys(pages)
      .filter((it) => it.startsWith(prefix))
      .sort()
    if (entries.length === 0) continue
    lines.push('', `## ${title}`, '', about, '')
    for (const path of entries) lines.push(`- [${titleOf(pages[path]!, path)}](${path})`)
  }
  return lines.join('\n') + '\n'
}

/**
 * The files to write, `{ project path: text }`, or `{}` when the README
 * already there ([current]) says it's this bundle. [cliText] is the
 * `netherforge` command to write beside the docs, when there is one.
 */
export async function agentDocFiles(
  version: string,
  current: string | null,
  cliText: string | null,
): Promise<Record<string, string>> {
  const pages = await loadDocPages()
  const stamp = fingerprint([
    version,
    ...agentDocPaths.flatMap((it) => [it, pages[it]!]),
    cliText ?? '',
  ])
  const readme = agentReadme(version, pages, stamp, cliText != null)
  if (current === readme) return {}
  const files: Record<string, string> = {}
  for (const [path, text] of Object.entries(pages)) files[`${AGENT_FILES.docs}/${path}`] = text
  if (cliText != null) files[AGENT_FILES.cli] = cliText
  // Last, so an interrupted write is redone next time.
  files[`${AGENT_FILES.docs}/README.md`] = readme
  return files
}
