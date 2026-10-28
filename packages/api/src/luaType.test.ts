import { describe, expect, it } from 'vitest'
import { acceptsNil, LuaTypeError, namedTypes, parseLuaType, withoutNil } from './luaType.ts'

const named = (name: string) => ({ kind: 'named', name })
const primitive = (name: string) => ({ kind: 'primitive', name })

describe('parseLuaType', () => {
  it('reads primitives and declared names', () => {
    expect(parseLuaType('string')).toEqual(primitive('string'))
    expect(parseLuaType('Centity')).toEqual(named('Centity'))
    expect(parseLuaType('nf.Event')).toEqual(named('nf.Event'))
  })

  it('reads optionals, arrays and maps', () => {
    expect(parseLuaType('Player?')).toEqual({ kind: 'optional', type: named('Player') })
    expect(parseLuaType('string[]?')).toEqual({
      kind: 'optional',
      type: { kind: 'array', item: primitive('string') },
    })
    expect(parseLuaType('table<integer, Item>')).toEqual({
      kind: 'map',
      key: primitive('integer'),
      value: named('Item'),
    })
    expect(parseLuaType('table')).toEqual(primitive('table'))
  })

  it('reads unions and string literals', () => {
    expect(parseLuaType('"left"|"right"')).toEqual({
      kind: 'union',
      types: [
        { kind: 'literal', value: 'left' },
        { kind: 'literal', value: 'right' },
      ],
    })
    expect(parseLuaType('(string|number)[]')).toEqual({
      kind: 'array',
      item: { kind: 'union', types: [primitive('string'), primitive('number')] },
    })
  })

  it('reads functions, with optional parameters, varargs and several returns', () => {
    expect(parseLuaType('fun(event: ClickEvent): boolean?')).toEqual({
      kind: 'fun',
      params: [{ name: 'event', type: named('ClickEvent'), optional: false }],
      returns: [{ kind: 'optional', type: primitive('boolean') }],
    })
    expect(parseLuaType('fun(...: any)')).toEqual({
      kind: 'fun',
      params: [],
      vararg: primitive('any'),
      returns: [],
    })
    expect(parseLuaType('fun(s: string, i?: integer): integer?, integer?')).toMatchObject({
      params: [{ name: 's' }, { name: 'i', optional: true }],
      returns: [{ kind: 'optional' }, { kind: 'optional' }],
    })
    expect(parseLuaType('fun()')).toEqual({ kind: 'fun', params: [], returns: [] })
  })

  it('reads an async function, which may wait', () => {
    expect(parseLuaType('async fun(...: any)')).toEqual({
      kind: 'fun',
      params: [],
      vararg: primitive('any'),
      returns: [],
      async: true,
    })
    expect(parseLuaType('(async fun())?')).toMatchObject({ type: { kind: 'fun', async: true } })
  })

  it('gives a trailing return to the innermost function, unless a parameter follows', () => {
    expect(parseLuaType('fun(s: string): fun(): integer, integer')).toMatchObject({
      returns: [{ kind: 'fun', returns: [primitive('integer'), primitive('integer')] }],
    })
    expect(parseLuaType('fun(a: fun(): string, b: number)')).toMatchObject({
      params: [{ name: 'a', type: { kind: 'fun', returns: [primitive('string')] } }, { name: 'b' }],
    })
  })

  it('refuses what the grammar does not allow, saying where', () => {
    for (const bad of [
      '',
      'string?)',
      'table<string>',
      'fun(a: string',
      '"open',
      'Player Node',
      'fun(...: any, a: string)',
      '|string',
      'async string',
    ]) {
      expect(() => parseLuaType(bad), bad).toThrow(LuaTypeError)
    }
    expect(() => parseLuaType('table<string>')).toThrow(/expected ","/)
  })
})

describe('type helpers', () => {
  it('lists the declared names a type mentions', () => {
    expect(namedTypes(parseLuaType('fun(e: ClickEvent, n: Node?): table<string, Item[]>'))).toEqual(
      ['ClickEvent', 'Node', 'Item'],
    )
  })

  it('knows which types accept nil, and what is left without it', () => {
    expect(acceptsNil(parseLuaType('Player?'))).toBe(true)
    expect(acceptsNil(parseLuaType('string|nil'))).toBe(true)
    expect(acceptsNil(parseLuaType('Player'))).toBe(false)
    expect(withoutNil(parseLuaType('Player?'))).toEqual(named('Player'))
    expect(withoutNil(parseLuaType('string|number|nil'))).toEqual({
      kind: 'union',
      types: [primitive('string'), primitive('number')],
    })
  })
})
