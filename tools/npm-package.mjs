#!/usr/bin/env node
/**
 * Stages `@netherforge/cli` for npm in [out]: the built command
 * (`apps/cli/dist/netherforge.mjs`, one self-contained file), a package.json
 * naming it as the `netherforge` bin, a README and the licence. Nothing of the
 * monorepo's own package.json goes with it: its dependencies are bundled into
 * the file, so the published package has none.
 *
 *   node tools/npm-package.mjs <out>
 *
 * The release's `cli` job stages and packs it as a check; `npm.yml` publishes
 * it when a release is published. See .claude/skills/release/SKILL.md.
 */
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'

const repo = path.resolve(import.meta.dirname, '..')
const out = process.argv[2]
if (!out) {
  console.error('usage: node tools/npm-package.mjs <out>')
  process.exit(2)
}
const script = path.join(repo, 'apps/cli/dist/netherforge.mjs')
if (!existsSync(script)) {
  console.error(`${script} isn't built: pnpm --filter @netherforge/cli build`)
  process.exit(2)
}
const version = readFileSync(path.join(repo, 'VERSION'), 'utf8').trim()
// Provenance ties the package to the repository that built it: the one this runs in on CI.
const github = `https://github.com/${process.env.GITHUB_REPOSITORY || 'jayson-clark/netherforge'}`

const manifest = {
  name: '@netherforge/cli',
  version,
  description:
    'The netherforge command: set up, check, format, test and preview NetherForge projects (Minecraft server content) from a terminal, CI or a coding agent',
  keywords: ['netherforge', 'minecraft', 'paper', 'lua', 'cli'],
  license: 'Apache-2.0',
  homepage: github,
  repository: { type: 'git', url: `git+${github}.git`, directory: 'apps/cli' },
  bugs: `${github}/issues`,
  type: 'module',
  bin: { netherforge: 'netherforge.mjs' },
  // Generators import the SDK from it: `import { project } from "@netherforge/cli"`.
  exports: { '.': './netherforge.mjs' },
  files: ['netherforge.mjs'],
  engines: { node: '>=22' },
}

const readme = `# @netherforge/cli

The \`netherforge\` command for [NetherForge](${github}) projects: the same
checks, formatting, tests and previews as the NetherForge editor, from a
terminal, CI or a coding agent.

\`\`\`sh
npx @netherforge/cli setup      # write .netherforge/ (schemas, LuaLS stubs, docs) on a fresh clone
npx @netherforge/cli check      # validate the project as the editor's Problems panel does
npx @netherforge/cli format     # rewrite project JSON in canonical form
npx @netherforge/cli test       # run the project's *_test.lua files
\`\`\`

Or install it once: \`npm install --global @netherforge/cli\`, then \`netherforge help\`.
Node 22 or newer; \`test\` also needs Java 21 or newer.

The version is NetherForge's: use the one that matches the editor and the plugin
your server runs (\`npx @netherforge/cli@${version} …\`).
`

rmSync(out, { recursive: true, force: true })
mkdirSync(out, { recursive: true })
copyFileSync(script, path.join(out, 'netherforge.mjs'))
chmodSync(path.join(out, 'netherforge.mjs'), 0o755)
copyFileSync(path.join(repo, 'LICENSE'), path.join(out, 'LICENSE'))
writeFileSync(path.join(out, 'package.json'), JSON.stringify(manifest, null, 2) + '\n')
writeFileSync(path.join(out, 'README.md'), readme)
console.log(`Staged @netherforge/cli ${version} in ${out}`)
