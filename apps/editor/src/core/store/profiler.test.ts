import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ProfileSample } from '@netherforge/format/types'
import { openExampleApp, settle } from '@/testing/workspace'
import { addSample, emptyProfile, handlerKey, sortRows, TIMELINE_TICKS } from './profiler'

const tick = (n: number, nanos = 1_000) => ({ tick: n, nanos, phases: { timers: nanos } })

const sample = (from: number, extra: Partial<ProfileSample> = {}): ProfileSample => ({
  ticks: Array.from({ length: 20 }, (_, i) => tick(from + i)),
  ...extra,
})

describe('the profiler store', () => {
  afterEach(() => vi.useRealTimers())

  it('sums each function and scope over batches, keeping its worst', () => {
    const tower = { file: 'centities/tower/script.lua', line: 7 }
    let data = addSample(
      emptyProfile(),
      sample(1, {
        handlers: [
          {
            script: 'centity tower',
            kind: 'tick',
            source: tower,
            calls: 20,
            nanos: 400,
            max: 30,
            scopes: 2,
          },
        ],
        scopes: [{ scope: 'centity tower 1a2b3c4d', nanos: 200, calls: 20, max: 15 }],
      }),
    )
    data = addSample(
      data,
      sample(21, {
        handlers: [
          { script: 'centity tower', kind: 'tick', source: tower, calls: 20, nanos: 600, max: 25 },
        ],
        scopes: [{ scope: 'centity tower 1a2b3c4d', nanos: 300, calls: 20, max: 40 }],
      }),
    )
    expect(data.handlers.get(handlerKey('centity tower', 'tick', tower))).toEqual({
      key: handlerKey('centity tower', 'tick', tower),
      script: 'centity tower',
      kind: 'tick',
      source: tower,
      calls: 40,
      nanos: 1000,
      max: 30,
      scopes: 2,
    })
    expect(data.scopes.get('centity tower 1a2b3c4d')).toMatchObject({
      calls: 40,
      nanos: 500,
      max: 40,
    })
    expect(data.measured).toBe(40)
    expect(data.nanos).toBe(40_000)
  })

  it('keeps the last ticks for the timeline', () => {
    let data = emptyProfile()
    for (let from = 1; from < 400; from += 20) data = addSample(data, sample(from))
    expect(data.ticks).toHaveLength(TIMELINE_TICKS)
    expect(data.ticks.at(-1)!.tick).toBe(400)
  })

  it('sorts by total, mean, max or calls, ties by name', () => {
    const rows = [
      { key: 'a', nanos: 100, calls: 10, max: 50 },
      { key: 'b', nanos: 90, calls: 1, max: 90 },
      { key: 'c', nanos: 100, calls: 100, max: 1 },
    ]
    expect(sortRows(rows, 'total').map((it) => it.key)).toEqual(['a', 'c', 'b'])
    expect(sortRows(rows, 'mean').map((it) => it.key)).toEqual(['b', 'a', 'c'])
    expect(sortRows(rows, 'max').map((it) => it.key)).toEqual(['b', 'a', 'c'])
    expect(sortRows(rows, 'calls').map((it) => it.key)).toEqual(['c', 'a', 'b'])
  })

  it('subscribes when the bridge comes up and adds what the dev server streams', async () => {
    vi.useFakeTimers()
    const { backend, app } = await openExampleApp({ backend: { profileEveryMs: 1000 } })
    await app.run.getState().connect()
    await app.profiler.getState().connect()
    backend.testConnect()
    await settle()
    expect(backend.bridgeLog).toContainEqual({ method: 'profiler_subscribe', params: { on: true } })
    expect(app.profiler.getState().subscribed).toBe(true)

    // A batch a second, of 20 ticks each.
    await vi.advanceTimersByTimeAsync(2000)
    const state = app.profiler.getState()
    expect(state.measured).toBe(40)
    // examples/showcase's tower registers a click handler; the fake times it at its line.
    const tower = [...state.handlers.values()].find((it) => it.script === 'centity tower')
    expect(tower?.source?.file).toBe('centities/tower/script.lua')

    app.profiler.getState().reset()
    expect(app.profiler.getState().measured).toBe(0)
    // Stopping the server ends the stream.
    const stopping = app.run.getState().stop()
    await vi.advanceTimersByTimeAsync(3000)
    await stopping
    expect(app.profiler.getState().measured).toBe(0)
  })
})
