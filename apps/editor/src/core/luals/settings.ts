/**
 * The `Lua` settings the editor's language client gives lua-language-server,
 * on top of the project's `.luarc.json` (which wins where both say something).
 */
import { KINDS, type KindId } from '@/core/format'
import { PACKAGE_STUBS } from '@netherforge/project-support'

/**
 * The arguments NetherForge's LuaLS plugin takes: each scripted kind whose
 * resources are folders with a JSON main file naming their script
 * (`centities=centity.json`), from format's kind table; where modules are
 * (`modules=modules`); and where the packages' module stubs are
 * (`packages=.netherforge/luals/packages`).
 */
export const pluginArgs = (): string[] => [
  ...(Object.keys(KINDS) as KindId[]).flatMap((kind) => {
    const { folder, layout, main, scripted, contents } = KINDS[kind]
    return scripted && layout === 'folder' && contents === 'json' && main
      ? [`${folder}=${main}`]
      : []
  }),
  // Where modules are, group folders and all, for `require("greeter")`.
  `modules=${KINDS.module.folder}`,
  // Where the packages' exported modules' stubs are, for `require("library:greetings")`.
  `packages=${PACKAGE_STUBS}`,
]

export function lualsSettings(plugin: string) {
  return {
    // `require` beside a resource's script and of a module in a group folder, as the server resolves it.
    runtime: { plugin, pluginArgs: pluginArgs() },
    // LuaLS would otherwise offer to download addons and ask about third-party libraries.
    addonManager: { enable: false },
    workspace: { checkThirdParty: 'Disable' },
    telemetry: { enable: false },
  }
}
