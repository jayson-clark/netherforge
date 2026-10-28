/**
 * The validation worker's side: format's `ProjectValidator` for the open
 * project, brought up to date by each request and asked again. It runs in
 * the worker (`validation.worker.ts`), off the main thread.
 */
import { ProjectValidator } from '@/core/format'
import type { ValidationApi } from './requests'

export function createValidator(): ValidationApi {
  let validator = new ProjectValidator()
  return {
    validate(request) {
      if (request.reset) validator = new ProjectValidator()
      for (const [path, text] of Object.entries(request.files)) validator.setFile(path, text)
      for (const path of request.deleted) validator.deleteFile(path)
      if (request.gameData !== undefined) validator.setGameData(request.gameData)
      if (request.packages !== undefined) validator.setPackages(request.packages)
      if (request.sizes !== undefined) validator.setSizes(request.sizes)
      return validator.validate()
    },
    check(request) {
      const once = new ProjectValidator()
      for (const [path, text] of Object.entries(request.files)) once.setFile(path, text)
      once.setGameData(request.gameData)
      once.setPackages(request.packages)
      return once.validate()
    },
  }
}
