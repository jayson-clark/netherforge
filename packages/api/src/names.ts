import type { NameKind } from './types.ts'

/**
 * The LuaLS type a parameter that names a project thing (`names`) is
 * declared as. `nf.lua` declares each as a plain `string`; the editor writes a
 * project's own names as more of the same alias (`---@alias NodeName "top"`),
 * which LuaLS merges, so `this:node("` completes the project's node names
 * while any string still type-checks.
 */
export const NAME_ALIASES: Record<NameKind, string> = {
  centity: 'CentityId',
  menu: 'MenuId',
  dialog: 'DialogId',
  particle_effect: 'ParticleEffectId',
  cutscene: 'CutsceneId',
  item: 'ItemId',
  block: 'BlockId',
  recipe: 'RecipeId',
  loot_table: 'LootTableId',
  buff: 'BuffId',
  advancement: 'AdvancementId',
  animation: 'AnimationName',
  node: 'NodeName',
  button: 'ButtonKey',
  glyph: 'GlyphRef',
  skin: 'SkinRef',
  map: 'MapId',
  structure: 'StructureId',
  setting: 'SettingName',
  screen: 'ScreenId',
  video: 'VideoId',
  server: 'NetworkServerName',
  channel: 'NetworkChannelName',
  network_map: 'NetworkMapName',
}
