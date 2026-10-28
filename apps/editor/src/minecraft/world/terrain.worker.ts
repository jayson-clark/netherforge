/** The map preview's worker: `TerrainCore` over this worker's messages. */
import { serveTerrain, type ToTerrain } from './terrain'

const scope = self as unknown as {
  postMessage(message: unknown, transfer: Transferable[]): void
  onmessage: ((event: MessageEvent<ToTerrain>) => void) | null
}

serveTerrain({
  post: (message, transfer) => scope.postMessage(message, transfer ?? []),
  listen: (handler) => {
    scope.onmessage = (event) => handler(event.data)
  },
})
