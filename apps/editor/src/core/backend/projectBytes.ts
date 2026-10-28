import type { Backend } from './types'

/**
 * A project file's bytes, through the backend's `nfproject://` protocol (path
 * scoped like every file command): the binary files the editor reads but
 * never edits as text, like structures and `level.dat`. [stamp] is the
 * workspace's `fileStamps` entry, so a changed file is read again.
 */
export async function readProjectBytes(
  backend: Backend,
  path: string,
  stamp?: number,
): Promise<Uint8Array> {
  let response: Response
  try {
    response = await fetch(backend.projectFileUrl(path, stamp))
  } catch {
    throw new Error(`Couldn't read ${path}`)
  }
  if (!response.ok) throw new Error(`Couldn't read ${path} (${response.status})`)
  return new Uint8Array(await response.arrayBuffer())
}
