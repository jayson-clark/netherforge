import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { CentityFile } from '@/core/format'
import { commit, createHistory } from '@/core/store/history'
import { exampleFiles } from '@/testing/fixtures'
import { usePose } from './pose'

const TOWER = 'centities/tower/centity.json'
const tower = JSON.parse(exampleFiles[TOWER]!) as CentityFile

/** The tower with its first node parented to itself, which format refuses to compose. */
function broken(model: CentityFile): CentityFile {
  const [name, node] = Object.entries(model.nodes ?? {})[0]!
  return { ...model, nodes: { ...model.nodes, [name]: { ...node, parent: name } } }
}

describe('usePose', () => {
  it('poses the present model', () => {
    const { result } = renderHook(() => usePose(TOWER, createHistory(tower), null, 0))
    expect(result.current.stale).toBe(false)
    expect(result.current.pose?.nodes.length).toBeGreaterThan(0)
  })

  it("shows the latest model in the history that composes while the present one doesn't", () => {
    const history = commit(createHistory(tower), broken(tower))
    const { result } = renderHook(() => usePose(TOWER, history, null, 0))
    expect(result.current.stale).toBe(true)
    expect(result.current.problems.length).toBeGreaterThan(0)
    expect(result.current.pose?.nodes.length).toBe(Object.keys(tower.nodes ?? {}).length)
  })

  it('shows nothing when nothing in the history composes', () => {
    const { result } = renderHook(() => usePose(TOWER, createHistory(broken(tower)), null, 0))
    expect(result.current).toMatchObject({ pose: null, stale: true })
  })
})
