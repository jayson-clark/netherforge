import { beforeEach, describe, expect, it } from 'vitest'
import type { WorkspaceStore } from '@/core/store/workspace'
import { openExampleWorkspace } from '@/testing/workspace'
import { distinct, toggled, viewOf } from './views'

const TOWER = 'centities/tower/centity.json'
const PARTICLES = 'particles/shockwave/effect.json'
const CUTSCENE = 'cutscenes/flyby.json'
const TERRAIN = 'terrain/ruby_hills.json'

let store: WorkspaceStore

beforeEach(async () => {
  ;({ workspace: store } = await openExampleWorkspace())
  await store.getState().openFile(TOWER)
})

describe('a typed document view', () => {
  it("reads as its kind's initial state until changed, and stores only what changed", () => {
    const view = viewOf(store, 'centity', TOWER)
    expect(view.state()).toMatchObject({ clip: null, gizmo: 'translate', showHitboxes: true })
    view.update({ gizmo: 'rotate' })
    view.update((state) => ({ showHitboxes: !state.showHitboxes }))
    expect(view.state()).toMatchObject({ gizmo: 'rotate', showHitboxes: false, time: 0 })
    expect(store.getState().views[TOWER]?.state).toEqual({ gizmo: 'rotate', showHitboxes: false })
  })

  it('selects several items, the last being the primary, each once', () => {
    const view = viewOf(store, 'centity', TOWER)
    expect(view.primary()).toBeNull()
    view.select('top', 'base', 'top')
    expect(view.selection()).toEqual(['base', 'top'])
    expect(view.primary()).toBe('top')
    view.toggle('base')
    expect(view.selection()).toEqual(['top'])
    view.toggle('flag')
    expect(view.selection()).toEqual(['top', 'flag'])
    expect(view.isSelected('flag')).toBe(true)
    view.select()
    expect(view.selection()).toEqual([])
  })

  it('follows edits to what is selected: renamed, moved or gone', () => {
    const view = viewOf(store, 'centity', TOWER)
    view.select('top', 'base')
    view.mapSelection((node) => (node === 'top' ? 'crown' : null))
    expect(view.selection()).toEqual(['crown'])
  })

  it('compares structured items by their kind’s key', async () => {
    await store.getState().openFile(PARTICLES)
    const view = viewOf(store, 'particle_effect', PARTICLES)
    view.select({ kind: 'emitter', emitter: 'ring' })
    view.toggle({ kind: 'key', emitter: 'ring', channel: 'radius', index: 1 })
    view.toggle({ kind: 'emitter', emitter: 'ring' })
    expect(view.selection()).toEqual([
      { kind: 'key', emitter: 'ring', channel: 'radius', index: 1 },
    ])
    expect(view.state()).toEqual({ tick: 0, playing: false, loop: false, camera: null })
  })
})

describe('a cutscene view', () => {
  it('keeps the playhead and the selected key with the document, not in the file', async () => {
    await store.getState().openFile(CUTSCENE)
    const view = viewOf(store, 'cutscene', CUTSCENE)
    expect(view.state()).toEqual({ time: 0, playing: false, camera: null })
    view.update({ time: 2.5 })
    view.select({ track: 'position', index: 1 })
    view.toggle({ track: 'cues', index: 0 })
    view.toggle({ track: 'position', index: 1 })
    expect(view.selection()).toEqual([{ track: 'cues', index: 0 }])
    expect(view.state().time).toBe(2.5)
    expect(store.getState().docs[CUTSCENE]?.dirty).toBeFalsy()
  })
})

describe('a terrain view', () => {
  it('keeps the seed and where the preview is looking with the document, not in the file', async () => {
    await store.getState().openFile(TERRAIN)
    const view = viewOf(store, 'terrain', TERRAIN)
    expect(view.state()).toEqual({
      seed: 1337,
      x: 0,
      z: 0,
      zoom: 1,
      detail: 4,
      alongX: true,
      at: 0,
      along: 0,
      width: 256,
      heights: null,
    })
    view.update({ seed: 99, x: 128 })
    view.update({ alongX: false, at: -40 })
    expect(view.state()).toMatchObject({ seed: 99, x: 128, alongX: false, at: -40 })
    expect(store.getState().docs[TERRAIN]?.dirty).toBeFalsy()
  })
})

describe('selection helpers', () => {
  const key = (it: string) => it
  it('drop repeats, keeping the last', () => {
    expect(distinct(['a', 'b', 'a', 'c'], key)).toEqual(['b', 'a', 'c'])
  })
  it('toggle an item in or out', () => {
    expect(toggled(['a'], 'b', key)).toEqual(['a', 'b'])
    expect(toggled(['a', 'b'], 'a', key)).toEqual(['b'])
  })
})
