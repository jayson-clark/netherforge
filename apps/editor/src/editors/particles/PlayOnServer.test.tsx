import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import type { ParticleEffectFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { PlayOnServer } from './PlayOnServer'
import { openExampleApp } from '@/testing/workspace'

const SPARKLE = 'particles/sparkle/effect.json'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
  await app.workspace.getState().openFile(SPARKLE)
  await app.run.getState().connect()
})

afterEach(cleanup)

/** What the editor sent the dev server, past what it sends on connecting. */
const sent = () =>
  backend.bridgeLog.filter((it) => it.method !== 'profiler_subscribe' && it.method !== 'settings')

/** Once the editor has sent [method]. */
const sentOnce = (method: string) =>
  waitFor(() => expect(sent().map((it) => it.method)).toContain(method))

function show(loop: boolean) {
  render(
    <AppProvider app={app}>
      <PlayOnServer path={SPARKLE} id="sparkle" loop={loop} />
    </AppProvider>,
  )
  return {
    play: screen.getByRole('button', { name: /Play on server/ }),
    stop: screen.getByRole('button', { name: /Stop/ }),
  }
}

describe('Play on server', () => {
  it('waits for the dev server', () => {
    const { play, stop } = show(false)
    expect((play as HTMLButtonElement).disabled).toBe(true)
    expect((stop as HTMLButtonElement).disabled).toBe(true)
  })

  it('saves a changed effect first, then plays it with the loop toggle, and stops', async () => {
    act(() => backend.testConnect())
    app.workspace.getState().edit<ParticleEffectFile>(SPARKLE, (draft) => {
      draft.duration = 30
    })
    const { play, stop } = show(true)
    expect((play as HTMLButtonElement).disabled).toBe(false)
    fireEvent.click(play)
    await sentOnce('play_particle_effect')
    expect(app.workspace.getState().docs[SPARKLE]?.dirty).toBe(false)
    expect(sent()).toEqual([
      { method: 'reload', params: { paths: [SPARKLE] } },
      { method: 'play_particle_effect', params: { effect: 'sparkle', loop: true } },
    ])
    fireEvent.click(stop)
    await sentOnce('stop_particle_effects')
    expect(backend.bridgeLog.at(-1)).toEqual({ method: 'stop_particle_effects' })
  })

  it('plays a saved effect without saving it again', async () => {
    act(() => backend.testConnect())
    fireEvent.click(show(false).play)
    await sentOnce('play_particle_effect')
    expect(sent()).toEqual([
      { method: 'play_particle_effect', params: { effect: 'sparkle', loop: false } },
    ])
  })
})
