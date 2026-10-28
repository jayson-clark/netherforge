import { realpathSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { run } from './main.ts'

// Imported by a project's generators, it's the SDK (`project()`); run with Node, it's the command.
export { project, type GeneratorProject, type ProjectResource } from './sdk.ts'

const entry = process.argv[1]
const ran = entry !== undefined && import.meta.url === pathToFileURL(realpathOrSelf(entry)).href

function realpathOrSelf(file: string): string {
  try {
    return realpathSync(file)
  } catch {
    return file
  }
}

if (ran) {
  const outcome = await run(process.argv.slice(2))
  for (const line of outcome.out) process.stdout.write(`${line}\n`)
  for (const line of outcome.err) process.stderr.write(`${line}\n`)
  process.exitCode = outcome.code
}
