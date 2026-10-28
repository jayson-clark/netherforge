/**
 * The API's LuaLS stubs as this project's lua-language-server reads them:
 * `nf.lua` and `surfaces/*.lua` from `@netherforge/api`, written into
 * `.netherforge/luals/` (`luals.ts`) with every function the project can't
 * use marked `---@deprecated`, saying why. LuaLS then flags a call to one
 * (its `deprecated` diagnostic) in the editor and in any other editor that
 * reads the project's `.luarc.json`.
 *
 * What a project can't use (`gates.json`, generated from the spec): a
 * function newer than the Minecraft version the project targets (`since`),
 * and one that needs something the project hasn't declared (`requires`,
 * `granted` in `@netherforge/api/requirements`). A function has one
 * definition in `nf.lua`, and LuaLS calls a call deprecated only when every
 * definition is, so the mark goes into the copy itself rather than a file
 * beside it.
 */
import type { Requirement } from '@netherforge/api'
import { granted, requirementSentence, type DeclaringManifest } from '@netherforge/api/requirements'

/** A function a project may not be able to use, as `nf.lua` declares it (`Player:ban`). */
export interface ApiGate {
  function: string
  /** The Minecraft version it needs. */
  since?: string
  /** What it needs declared. */
  requires?: Requirement
}

/** The API's stubs as generated, by path under `.netherforge/luals/` (`nf.lua`, `surfaces/centity.lua`), and its gates. */
export interface ApiStubs {
  files: Record<string, string>
  gates: ApiGate[]
}

/** What decides which functions a project can use: its target and its `netherforge.json`. */
export interface ProjectLimits {
  minecraft: string | null
  manifest: DeclaringManifest | null
}

/** `1.21.11` < `26.1` < `26.1.2`: Minecraft's versions compared part by part. */
export function versionBefore(a: string, b: string): boolean {
  const left = a.split('.').map(Number)
  const right = b.split('.').map(Number)
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const [x, y] = [left[i] ?? 0, right[i] ?? 0]
    if (x !== y) return x < y
  }
  return false
}

/** Why [project] can't use [gate], or null when it can. */
export function gateReason(gate: ApiGate, project: ProjectLimits): string | null {
  if (gate.since && project.minecraft && versionBefore(project.minecraft, gate.since))
    return `Needs Minecraft ${gate.since}; this project targets ${project.minecraft}.`
  if (gate.requires && !granted(project.manifest).has(gate.requires))
    return requirementSentence(gate.requires, gate.function)
  return null
}

/** [stubs]' files, `nf.lua` with each function [project] can't use marked `---@deprecated`. */
export function tailoredApiStubs(stubs: ApiStubs, project: ProjectLimits): Record<string, string> {
  const nf = stubs.files['nf.lua']
  if (nf === undefined) return stubs.files
  const reasons = new Map<string, string>()
  for (const gate of stubs.gates) {
    const reason = gateReason(gate, project)
    if (reason) reasons.set(gate.function, reason)
  }
  if (reasons.size === 0) return stubs.files
  const lines = nf.split('\n').flatMap((line) => {
    const name = /^function ([\w.:]+)\(/.exec(line)?.[1]
    const reason = name ? reasons.get(name) : undefined
    return reason ? [`---@deprecated ${reason}`, line] : [line]
  })
  return { ...stubs.files, 'nf.lua': lines.join('\n') }
}
