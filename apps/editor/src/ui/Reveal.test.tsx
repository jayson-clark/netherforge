import { act, cleanup, render, screen } from '@testing-library/react'
import { useRef, useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MAX_WAIT_MS, RevealGate, usePendingPicture, useReveal } from './Reveal'

function Picture({ pending }: { pending: boolean }) {
  usePendingPicture(pending)
  return <span>picture</span>
}

function Page({ page, pending }: { page: string; pending: boolean }) {
  const box = useRef<HTMLDivElement>(null)
  const { gate, shown } = useReveal(box, page)
  return (
    <div ref={box} data-testid="page" data-shown={shown}>
      <RevealGate value={gate}>
        <Picture pending={pending} />
      </RevealGate>
    </div>
  )
}

function Driven({ page }: { page: string }) {
  const [pending, setPending] = useState(true)
  return (
    <>
      <button onClick={() => setPending(false)}>finish</button>
      <Page page={page} pending={pending} />
    </>
  )
}

const shown = () => screen.getByTestId('page').getAttribute('data-shown')
const flush = () => act(async () => {})

describe('revealing a page of pictures', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('shows at once when nothing is pending', async () => {
    render(<Page page="a" pending={false} />)
    await flush()
    expect(shown()).toBe('true')
  })

  it('waits for a pending picture, and shows when it is ready', async () => {
    render(<Driven page="a" />)
    await flush()
    expect(shown()).toBe('false')
    act(() => screen.getByRole('button', { name: 'finish' }).click())
    await flush()
    expect(shown()).toBe('true')
  })

  it('gives up waiting after a while, and hides again for another page', async () => {
    const { rerender } = render(<Page page="a" pending />)
    await flush()
    expect(shown()).toBe('false')
    await act(async () => vi.advanceTimersByTime(MAX_WAIT_MS))
    expect(shown()).toBe('true')
    rerender(<Page page="b" pending />)
    expect(shown()).toBe('false')
  })
})
