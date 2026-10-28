/** The validation worker: format's validator for the open project, behind Comlink. */
import { expose, type Endpoint } from 'comlink'
import { createValidator } from './validator'

// `self` explicitly: it's the worker's scope in a browser and under vitest's
// @vitest/web-worker alike, where `globalThis` is the test's window. (The
// app's types are the DOM's, which call `self` a Window.)
expose(createValidator(), self as unknown as Endpoint)
