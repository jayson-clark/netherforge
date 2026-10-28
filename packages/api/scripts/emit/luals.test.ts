import { describe, expect, it } from 'vitest'
import { NAME_ALIASES } from '../../src/names.ts'
import { api } from '../../src/spec/index.ts'
import { luals, lualsSurfaces } from './luals.ts'

describe('the LuaLS stubs', () => {
  it('declare `this` once, as the union of every surface that has one', () => {
    const stub = luals(api)
    expect(stub).toContain(
      '---@type Centity|Menu|Dialog|ProjectItem|ProjectBlock|Screen\nthis = nil',
    )
    expect(stub.match(/^this = nil$/gm)).toHaveLength(1)
    // The line that narrows it is named where LuaLS shows the global's doc.
    expect(stub).toContain('`local this = this --[[@as Centity]]`')
    for (const gone of ['inventory', 'slot', 'button', 'dialog'])
      expect(stub).not.toMatch(new RegExp(`^${gone} = nil$`, 'm'))
  })

  it('write one stub per surface with globals of its own, typing them exactly', () => {
    const files = lualsSurfaces(api)
    expect([...files.keys()].sort()).toEqual([
      'block.lua',
      'centity.lua',
      'dialog.lua',
      'item.lua',
      'menu.lua',
      'screen.lua',
    ])
    expect(files.get('centity.lua')).toContain('---@type Centity\nthis = nil')
    expect(files.get('menu.lua')).toContain('---@type Menu\nthis = nil')
    expect(files.get('dialog.lua')).toContain('---@type Dialog\nthis = nil')
    expect(files.get('screen.lua')).toContain('---@type Screen\nthis = nil')
    for (const text of files.values()) expect(text.startsWith('---@meta\n')).toBe(true)
  })

  it('type an event handler per event, with `self` first on a method', () => {
    const stub = luals(api)
    expect(stub).toContain(
      '---@overload fun(self: Centity, event: "click", handler: fun(event: ClickEvent)): Subscription',
    )
    expect(stub).toContain(
      '---@overload fun(event: "player_join", handler: fun(event: PlayerJoinEvent)): Subscription',
    )
    // A class with one event types the handler in the signature: an overload that says the
    // same would be ambiguous to LuaLS.
    expect(stub).toMatch(
      /---@param handler fun\(event: MenuClickEvent\)[^\n]*\n(---.*\n)*function Slot:on\(/,
    )
    expect(stub).not.toContain('fun(self: Slot,')
    // Any other name on a class with custom events is one, with whatever fields it was raised with.
    expect(stub).toMatch(
      /---@param handler fun\(event: CustomEvent\)[^\n]*\n(---.*\n)*function Centity:on\(/,
    )
    expect(stub).toContain('---@class CustomEvent: Event\n---@field [string] any')
  })

  it('type a parameter that names a project thing as its alias, a plain string here', () => {
    const stub = luals(api)
    expect(stub).toContain('---@alias NodeName string')
    expect(stub).toMatch(/---@param name NodeName [^\n]*\n(---.*\n)*function Centity:node\(/)
    // Only the string in a union is the name.
    expect(stub).toMatch(/---@param \w+ MenuId\|MenuTemplate /)
    for (const alias of Object.values(NAME_ALIASES))
      expect(stub).toContain(`---@alias ${alias} string`)
  })

  it('type what `nf.wait_for` returns per handle and event', () => {
    const stub = luals(api)
    expect(stub).toContain(
      '---@overload fun(handle: Node, event: "collide", filter?: fun(event: CollideEvent): boolean): CollideEvent',
    )
    expect(stub).toContain(
      '---@overload fun(handle: nf, event: "player_join", filter?: fun(event: PlayerJoinEvent): boolean): PlayerJoinEvent',
    )
    expect(stub).toMatch(/---@return CustomEvent[^\n]*\n(---.*\n)*function nf\.wait_for\(/)
  })
})
