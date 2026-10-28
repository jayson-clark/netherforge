/**
 * Runs after `@vitest/web-worker` (vite.config.ts `setupFiles`). An app's
 * validation worker lives as long as the app, so a test file can end while a
 * worker it started is still importing its module. If that import then fails,
 * the stand-in dispatches its `error` event after vitest has put Node's
 * globals back over jsdom's: Node's `Event` isn't one jsdom's EventTarget
 * takes, and the throw is an unhandled error that fails the whole run (seen on
 * CI as "parameter 1 is not of type 'Event'"). Once a file's tests are over,
 * its leftover workers' events go nowhere.
 */
import { afterAll } from 'vitest'

let over = false
afterAll(() => {
  over = true
})

const StandIn = globalThis.Worker
globalThis.Worker = class extends StandIn {
  override dispatchEvent(event: Event): boolean {
    return over ? false : super.dispatchEvent(event)
  }
}
