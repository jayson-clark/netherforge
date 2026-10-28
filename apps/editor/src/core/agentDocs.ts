/**
 * The docs for coding agents as the editor writes them (`@netherforge/project-support`'s
 * `agentDocFiles`), with the `netherforge` command it carries. The command is a lazy chunk: it's
 * over a megabyte, and nothing needs it until a project is open.
 */
import { agentDocFiles } from '@netherforge/project-support'

// Missing until `@netherforge/cli` is built (`pnpm build`); the docs are still written without it.
const cli = import.meta.glob('/node_modules/@netherforge/cli/dist/netherforge.mjs', {
  query: '?raw',
  import: 'default',
}) as Record<string, () => Promise<string>>

/** The files to write, `{ project path: text }`, or `{}` when the README already there ([current]) is this bundle. */
export async function editorAgentDocFiles(
  version: string,
  current: string | null,
): Promise<Record<string, string>> {
  const [cliText = null] = await Promise.all(Object.values(cli).map((load) => load()))
  return agentDocFiles(version, current, cliText)
}
