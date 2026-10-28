import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import type { GameDataBundle } from '@netherforge/format/types'
import type { VideoFile } from '@/core/format'
import { AppProvider } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { staleness, VideoEditor } from './VideoEditor'

const BOUNCE = 'videos/bounce/video.json'

afterEach(cleanup)

/** Game data with a made-up palette (no game's), for converting. */
const game: GameDataBundle = {
  minecraft: '26.3',
  mapColors: [0, 0, 0, 0, -16777216, -1],
  schema: 3,
}

async function open(options: { ffmpeg: boolean }) {
  const { app } = await openExampleApp({
    backend: { ffmpeg: options.ffmpeg, gameData: { '26.3': game } },
  })
  await app.workspace.getState().openFile(BOUNCE)
  render(
    <AppProvider app={app}>
      <VideoEditor path={BOUNCE} />
    </AppProvider>,
  )
  return app
}

describe('the video editor', () => {
  it('says what its converted frames are', async () => {
    await open({ ffmpeg: false })
    expect(
      await screen.findByText(/Converted: 20 frames, 1 by 1 maps at 10 a second, silent/),
    ).toBeDefined()
    // Its source isn't in the example, so there's nothing to convert from.
    expect(screen.getByRole('button', { name: 'Convert again' })).toHaveProperty('disabled', true)
  })

  it('converts a source put beside it, and says why it failed without ffmpeg', async () => {
    const app = await open({ ffmpeg: true })
    await app.workspace
      .getState()
      .writeBinary('videos/bounce/bounce.mp4', new Uint8Array([1, 2, 3]))
    const user = userEvent.setup()
    const convert = await screen.findByRole('button', { name: 'Convert again' })
    await screen.findByText(/Converted: 20 frames/)
    await user.click(convert)
    expect(await screen.findByText(/Converted 10 frames \(1\.0 s\)/)).toBeDefined()
    expect(
      await screen.findByText(/Converted: 10 frames, 1 by 1 maps at 10 a second/),
    ).toBeDefined()
  })

  it('says how converted frames differ from the file', () => {
    const file: VideoFile = { source: 'a.mp4', width: 2, height: 1 }
    const info = {
      converted: true,
      columns: 1,
      rows: 1,
      fps: 10,
      frames: 4,
      audio: false,
      bytes: 9,
      sourcePresent: true,
      sourceChanged: true,
    }
    expect(staleness(file, info)).toEqual([
      'it was converted 1 by 1 maps',
      'at 10 frames a second',
      'from a source that has changed since',
    ])
    expect(
      staleness({ ...file, width: 1, height: 1, fps: 10 }, { ...info, sourceChanged: false }),
    ).toEqual([])
  })
})
