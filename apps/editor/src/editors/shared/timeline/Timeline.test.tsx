import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { moveKey, removeKey } from './keys'
import { Timeline, type KeyRef, type TimelineKey } from './Timeline'

afterEach(cleanup)

/** A timeline over one track of keys, in ticks, keeping its keys and selection like an editor. */
function Harness({ gesture }: { gesture: { begin: () => void; end: () => void } }) {
  const [keys, setKeys] = useState<TimelineKey[]>([{ time: 2 }, { time: 5, easing: 'step' }])
  const [selected, setSelected] = useState<KeyRef | null>(null)
  const [time, setTime] = useState(0)
  return (
    <>
      <Timeline
        scale={{ unit: 'ticks', length: 20 }}
        time={time}
        onSeek={setTime}
        playback={{ playing: false, onToggle: () => {}, time: <span>{time}</span> }}
        tracks={[{ id: 'size', label: 'size', keys }]}
        selectedKey={selected}
        onSelectKey={setSelected}
        onMoveKey={(ref, to) => {
          const next = [...keys]
          const index = moveKey(next, ref.index, to)
          setKeys(next)
          setSelected({ track: ref.track, index })
          return index
        }}
        onDeleteKey={(ref) => {
          const next = [...keys]
          removeKey(next, ref.index)
          setKeys(next)
          setSelected(null)
        }}
        gesture={gesture}
        keyName={(track, index) => `${track.label} key ${index + 1}`}
      />
      <output aria-label="Keys">{keys.map((it) => it.time).join(',')}</output>
      <output aria-label="Selected">{selected ? `${selected.track}:${selected.index}` : ''}</output>
    </>
  )
}

describe('Timeline', () => {
  it('moves the playhead from the keyboard', async () => {
    render(<Harness gesture={{ begin: () => {}, end: () => {} }} />)
    const playhead = screen.getByRole('slider', { name: 'Playhead' })
    playhead.focus()
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    expect((playhead as HTMLInputElement).value).toBe('2')
    await userEvent.keyboard('{End}')
    expect((playhead as HTMLInputElement).value).toBe('20')
  })

  it('selects a key on focus, moves it with the arrows as one gesture a press, and deletes it', async () => {
    const gesture = { begin: vi.fn(), end: vi.fn() }
    render(<Harness gesture={gesture} />)
    const first = screen.getByRole('slider', { name: 'size key 1' })
    expect(first.getAttribute('aria-valuetext')).toBe('tick 2, linear')
    expect(screen.getByRole('slider', { name: 'size key 2' }).getAttribute('aria-valuetext')).toBe(
      'tick 5, step',
    )

    // Play, the playhead, then the keys.
    await userEvent.tab()
    await userEvent.tab()
    await userEvent.tab()
    expect(document.activeElement).toBe(first)
    expect(screen.getByLabelText('Selected').textContent).toBe('size:0')

    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByLabelText('Keys').textContent).toBe('3,5')
    expect(gesture.begin).toHaveBeenCalledTimes(1)
    expect(gesture.end).toHaveBeenCalledTimes(1)

    // Shift moves ten ticks: past the other key, which re-sorts them; the selection follows it.
    await userEvent.keyboard('{Shift>}{ArrowRight}{/Shift}')
    expect(screen.getByLabelText('Keys').textContent).toBe('5,13')
    expect(screen.getByLabelText('Selected').textContent).toBe('size:1')

    // Never past the end, nor before the start.
    await userEvent.keyboard('{Shift>}{ArrowRight}{/Shift}')
    expect(screen.getByLabelText('Keys').textContent).toBe('5,20')

    screen.getByRole('slider', { name: 'size key 2' }).focus()
    await userEvent.keyboard('{Delete}')
    expect(screen.getByLabelText('Keys').textContent).toBe('5')
  })
})
