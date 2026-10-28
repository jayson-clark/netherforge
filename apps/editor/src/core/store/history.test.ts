import { describe, expect, it } from 'vitest'
import {
  beginGesture,
  canRedo,
  canUndo,
  commit,
  createHistory,
  endGesture,
  lastStep,
  nextStep,
  redo,
  undo,
  untag,
} from './history'

describe('history', () => {
  it('undoes and redoes single edits', () => {
    let h = createHistory(0)
    h = commit(h, 1)
    h = commit(h, 2)
    h = undo(h)
    expect(h.present).toBe(1)
    h = undo(h)
    expect(h.present).toBe(0)
    expect(canUndo(h)).toBe(false)
    h = redo(h)
    expect(h.present).toBe(1)
    expect(canRedo(h)).toBe(true)
  })

  it('groups a gesture into one entry', () => {
    let h = createHistory(0)
    h = commit(h, 1)
    h = beginGesture(h)
    for (let value = 2; value <= 10; value += 1) h = commit(h, value)
    h = endGesture(h)
    expect(h.present).toBe(10)
    h = undo(h)
    expect(h.present).toBe(1)
    h = redo(h)
    expect(h.present).toBe(10)
  })

  it('a gesture with no edits leaves no entry', () => {
    let h = createHistory('a')
    h = endGesture(beginGesture(h))
    expect(canUndo(h)).toBe(false)
  })

  it('a new edit clears the redo stack', () => {
    let h = commit(commit(createHistory(0), 1), 2)
    h = undo(h)
    h = commit(h, 5)
    expect(canRedo(h)).toBe(false)
    expect(undo(h).present).toBe(1)
  })

  it('ignores an edit that changes nothing', () => {
    const h = createHistory({ a: 1 })
    expect(commit(h, h.present)).toBe(h)
  })

  it("keeps each step's time and transaction with it through undo and redo", () => {
    let h = commit(createHistory(0), 1)
    h = commit(h, 2, 7)
    const own = h.pastSteps[0]!
    expect(lastStep(h)).toEqual({ seq: expect.any(Number), tx: 7 })
    expect(lastStep(h)!.seq).toBeGreaterThan(own.seq)
    h = undo(h)
    expect(lastStep(h)).toBe(own)
    expect(nextStep(h)?.tx).toBe(7)
    h = redo(h)
    expect(lastStep(h)?.tx).toBe(7)
    // A forgotten transaction's step is the document's own.
    expect(lastStep(untag(h, 7))?.tx).toBeNull()
    expect(untag(h, 8)).toBe(h)
  })
})
