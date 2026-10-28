import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { BLOCK_REGISTRY } from '@/core/format'
import { AppProvider } from '@/state/providers'
import { exampleProject } from '@/testing/fixtures'
import { openExampleApp } from '@/testing/workspace'
import { GameBlockEditor } from './GameBlockEditor'

const BRICKS = 'game_blocks/bricks.json'

afterEach(cleanup)

describe('the game block editor', () => {
  it("draws the blocks it lists, a tag's from the game data", async () => {
    const { app } = await openExampleApp({
      project: {
        ...exampleProject,
        [BRICKS]: JSON.stringify({ blocks: ['minecraft:bricks', '#logs', '#minecraft:nope'] }),
      },
      backend: {
        gameData: {
          '26.3': {
            minecraft: '26.3',
            tags: {
              [BLOCK_REGISTRY]: { 'minecraft:logs': ['minecraft:oak_log', 'minecraft:birch_log'] },
            },
          },
        },
      },
    })
    await app.workspace.getState().openFile(BRICKS)
    render(
      <AppProvider app={app}>
        <GameBlockEditor path={BRICKS} />
      </AppProvider>,
    )
    const preview = await screen.findByRole('list', { name: 'Blocks preview' })
    expect(within(preview).getByText('bricks')).toBeTruthy()
    const logs = await within(preview).findByRole('listitem', { name: '#logs' })
    expect(within(logs).getByText('oak_log')).toBeTruthy()
    expect(within(logs).getByText('birch_log')).toBeTruthy()
    const nope = within(preview).getByRole('listitem', { name: '#minecraft:nope' })
    expect(within(nope).getByText(/Not a tag the game data knows/)).toBeTruthy()
  })
})
