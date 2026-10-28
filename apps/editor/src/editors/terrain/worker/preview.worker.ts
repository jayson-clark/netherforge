/** The terrain preview's worker: [createPreviewCore] behind Comlink. */
import { expose, type Endpoint } from 'comlink'
import { createPreviewCore } from './core'

// `self` explicitly, as the validation worker does: the worker's scope in a browser and under vitest alike.
expose(createPreviewCore(), self as unknown as Endpoint)
