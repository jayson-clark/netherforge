/**
 * The main thread's end of the validation worker: Comlink over a module
 * worker that Vite bundles with its own copy of format's JS build.
 */
import { wrap, type Remote } from 'comlink'
import type { ProjectValidation } from '@/core/format'
import type { CheckRequest, ValidationApi, ValidationRequest } from './requests'

export interface ValidationClient {
  validate(request: ValidationRequest): Promise<ProjectValidation>
  check(request: CheckRequest): Promise<ProjectValidation>
  /** Stops the worker; nothing more is answered. */
  dispose(): void
}

/** A worker of its own. Under vitest, @vitest/web-worker runs the same module behind the same messages. */
export function validationWorker(): ValidationClient {
  const worker = new Worker(new URL('./validation.worker.ts', import.meta.url), {
    type: 'module',
    name: 'validation',
  })
  const api: Remote<ValidationApi> = wrap<ValidationApi>(worker)
  // A worker that fails to start (or dies) answers nothing: every request fails instead of waiting forever.
  const failed = new Promise<never>((_, reject) => {
    worker.addEventListener('error', (event) =>
      reject(new Error(event.message || 'The validation worker stopped')),
    )
  })
  failed.catch(() => {})
  return {
    validate: (request) => Promise.race([api.validate(request), failed]),
    check: (request) => Promise.race([api.check(request), failed]),
    dispose: () => worker.terminate(),
  }
}
