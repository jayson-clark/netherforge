import { describe, expect, it } from 'vitest'
import { api } from '../../src/spec/index.ts'
import type { ApiSpec, EventSpec } from '../../src/types.ts'
import { bindings } from './bindings.ts'
import { dispatchKotlin, platformKotlin, raisedEvents } from './raised.ts'

/** The real spec with nf's event [name] changed by [change]. */
function withEvent(name: string, change: (event: EventSpec) => void): ApiSpec {
  const copy = structuredClone(api)
  const nf = copy.classes.find((it) => it.name === 'nf')!
  const event = nf.events!.find((it) => it.name === name)!
  change(event)
  return copy
}

const raise = (spec: ApiSpec) => raisedEvents(spec, bindings(spec))

describe('raised events', () => {
  const events = raise(api)
  const platform = platformKotlin(events)
  const dispatch = dispatchKotlin(events)

  it('gives every raised event a sink method and a dispatch', () => {
    expect(events.length).toBeGreaterThan(50)
    for (const event of events) {
      expect(platform).toContain(`fun ${event.method}(event: GameEvent.${event.payload.name})`)
      expect(dispatch).toContain(
        `override fun ${event.method}(event: GameEvent.${event.payload.name})`,
      )
    }
  })

  it('watches exactly the events the spec says are watched', () => {
    const watched = events.filter((it) => it.raised.watched).map((it) => it.watched)
    expect(watched).toContain('PLAYER_MOVE')
    const enumBody = platform.slice(
      platform.indexOf('enum class WatchedEvent'),
      platform.indexOf('class GameEventDelivery'),
    )
    const entries = [...enumBody.matchAll(/^ {4}([A-Z_]+),?$/gm)].map((it) => it[1])
    expect(entries).toEqual(watched)
  })

  it('hears a player event on the player first, then on nf', () => {
    const food = dispatch.slice(dispatch.indexOf('override fun playerChangeFood'))
    expect(food).toContain(
      'val stages = listOf(Events.PLAYER_CHANGE_FOOD to first, Events.NF_PLAYER_CHANGE_FOOD to null)',
    )
    expect(food).toContain('if (!session.listening(stages)) return false')
  })

  it('keeps a writable number within its bounds', () => {
    const food = dispatch.slice(dispatch.indexOf('override fun playerChangeFood'))
    expect(food).toContain('event.food = payload.food.coerceIn(0L, 20L).toInt()')
  })

  it('hands a lazy field over as a function, called only when something listens', () => {
    expect(platform).toMatch(/val blocks: \(\) -> List<BlockRef>/)
    const explode = dispatch.slice(dispatch.indexOf('override fun entityExplode'))
    expect(explode.indexOf('event.blocks()')).toBeGreaterThan(
      explode.indexOf('session.listening(stages)'),
    )
  })

  it('refuses a field that is lazy and writable', () => {
    expect(() =>
      raise(
        withEvent('player_change_food', (it) => {
          it.raised = { ...it.raised, lazy: ['food'] }
        }),
      ),
    ).toThrow(/food can't be lazy and writable/)
  })

  it('refuses bounds on a field that is not writable', () => {
    expect(() =>
      raise(
        withEvent('player_change_food', (it) => {
          it.raised = { ...it.raised, bounds: { item: { min: 0 } } }
        }),
      ),
    ).toThrow(/bounds for item, which isn't a writable field/)
  })

  it('refuses to hear an event first on a class that does not have it', () => {
    expect(() =>
      raise(
        withEvent('player_change_food', (it) => {
          it.raised = {
            ...it.raised,
            first: { class: 'World', event: 'change_food', field: 'player' },
          }
        }),
      ),
    ).toThrow(/heard first as World change_food, which World doesn't have/)
  })

  it('refuses raised on an event that is not on nf', () => {
    const copy = structuredClone(api)
    const player = copy.classes.find((it) => it.name === 'Player')!
    player.events![0]!.raised = {}
    expect(() => raise(copy)).toThrow(/only an nf event says how it's raised/)
  })
})
