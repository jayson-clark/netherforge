/**
 * Writes the spec from the Kotlin runtime's `platform/` interfaces, one spec file per Kotlin
 * file: how the spec was first written. Once the Kotlin side is generated from the spec this
 * goes; until then `kotlin.test.ts` holds the two to each other, and a change to a Kotlin
 * interface is made in the spec with `pnpm --filter @netherforge/platform extract`.
 */
import { readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { readKotlin } from '../src/kotlin.ts'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '../../..')
export const KOTLIN_PLATFORM = path.join(
  root,
  'apps/plugin/runtime/src/main/kotlin/dev/netherforge/plugin/platform',
)
const out = path.join(here, '../src/spec')

/** `PlayerAdmin.kt` → `playerAdmin`. */
export const specName = (file: string) => file[0]!.toLowerCase() + file.slice(1, -'.kt'.length)

const files = readdirSync(KOTLIN_PLATFORM)
  .filter((it) => it.endsWith('.kt'))
  .sort()
for (const old of readdirSync(out)) rmSync(path.join(out, old))
for (const file of files) {
  const spec = readKotlin(readFileSync(path.join(KOTLIN_PLATFORM, file), 'utf8'), file)
  writeFileSync(
    path.join(out, `${specName(file)}.ts`),
    `import type { PlatformFile } from '../types.ts'\n\nexport default ${JSON.stringify(spec, null, 2)} satisfies PlatformFile\n`,
  )
}
writeFileSync(
  path.join(here, '../src/index.ts'),
  [
    '// The spec, one file per area. Written first by scripts/extract.ts from the Kotlin runtime.',
    "import type { PlatformFile } from './types.ts'",
    ...files.map((f) => `import ${specName(f)} from './spec/${specName(f)}.ts'`),
    '',
    "export * from './types.ts'",
    '',
    '/** Every file of the spec. */',
    `export const spec: PlatformFile[] = [${files.map(specName).join(', ')}]`,
    '',
  ].join('\n'),
)
