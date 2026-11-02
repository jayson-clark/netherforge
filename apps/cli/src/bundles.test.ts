import { createPublicKey, verify } from 'node:crypto'
import { cpSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createServer, type IncomingMessage } from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { inflateRawSync } from 'node:zlib'
import * as nf from '@netherforge/format'
import { GAME_DATA_SCHEMA } from '@netherforge/format/constants'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { run } from './main.ts'

const example = path.resolve(import.meta.dirname, '../../../examples/showcase')

let tmp: string
let project: string
let env: NodeJS.ProcessEnv

beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-bundles-'))
  project = path.join(tmp, 'project')
  cpSync(example, project, {
    recursive: true,
    filter: (source) => path.relative(example, source) !== '.netherforge',
  })
  cpSync(path.resolve(example, '../library'), path.join(tmp, 'library'), { recursive: true })
  env = { NETHERFORGE_DATA_DIR: path.join(tmp, 'data') }
})

afterEach(() => rmSync(tmp, { recursive: true, force: true }))

/** Whether [signature] is [key]'s (`ed25519:<base64>`) over [text]. */
function verifies(key: string, signature: string, text: string): boolean {
  const raw = Buffer.from(key.replace(/^ed25519:/, ''), 'base64')
  const spki = Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), raw])
  return verify(
    null,
    Buffer.from(text),
    createPublicKey({ key: spki, format: 'der', type: 'spki' }),
    Buffer.from(signature, 'base64'),
  )
}

/** The manifest's text without its signatures, as format writes it: what's signed. */
function signedText(manifest: Record<string, unknown>): string {
  const { signatures, ...rest } = manifest
  void signatures
  return (
    JSON.parse(nf.canonicalize('bundle', 'netherforge-bundle.json', JSON.stringify(rest))) as {
      text: string
    }
  ).text
}

describe('signing', () => {
  it('signs a bundle as it builds, and a countersignature adds another over the same text', async () => {
    const author = path.join(tmp, 'author.key')
    const network = path.join(tmp, 'network.key')
    const made = await run(['keygen', author], env)
    expect(made.code).toBe(0)
    const authorKey = made.out[1]!
    expect(authorKey).toMatch(/^ed25519:[A-Za-z0-9+/]{43}=$/)
    expect(readFileSync(`${author}.pub`, 'utf8').trim()).toBe(authorKey)
    await run(['keygen', network], env)

    const out = path.join(tmp, 'bundle')
    const built = await run(['build', project, '--out', out, '--sign', author], env)
    expect(built.err).toEqual([])
    expect(built.code).toBe(0)
    expect(built.out.join('\n')).toContain(`Signed as ${authorKey}`)
    const signed = await run(['sign', out, '--key', network], env)
    expect(signed.code).toBe(0)

    const manifest = JSON.parse(
      readFileSync(path.join(out, 'netherforge-bundle.json'), 'utf8'),
    ) as Record<string, unknown> & {
      signatures: { key: string; signature: string }[]
    }
    expect(manifest.signatures).toHaveLength(2)
    for (const { key, signature } of manifest.signatures)
      expect(verifies(key, signature, signedText(manifest))).toBe(true)
    // Signing again with the same key replaces its signature.
    await run(['sign', out, '--key', network], env)
    expect(
      JSON.parse(readFileSync(path.join(out, 'netherforge-bundle.json'), 'utf8')).signatures,
    ).toHaveLength(2)
  })

  it('archives a bundle as one file, the same bytes every time', async () => {
    const out = path.join(tmp, 'bundle')
    const first = await run(['build', project, '--out', out, '--archive'], env)
    expect(first.code).toBe(0)
    const hash = /sha256 ([0-9a-f]{64})/.exec(first.out.join('\n'))![1]
    expect(existsSync(`${out}.nfbundle`)).toBe(true)
    const again = await run(['build', project, '--out', out, '--archive'], env)
    expect(again.out.join('\n')).toContain(hash!)
    // The archive's first entry, read back: a file of the bundle.
    const zip = readFileSync(`${out}.nfbundle`)
    expect(zip.readUInt32LE(0)).toBe(0x04034b50)
    const nameLength = zip.readUInt16LE(26)
    const name = zip.subarray(30, 30 + nameLength).toString()
    const packed = zip.subarray(30 + nameLength, 30 + nameLength + zip.readUInt32LE(18))
    expect(inflateRawSync(packed).equals(readFileSync(path.join(out, name)))).toBe(true)
  })
})

describe('publish', () => {
  it('uploads the archive and its listing, signed for S3, and says what a server runs', async () => {
    const got: { method: string; url: string; auth: string; body: Buffer }[] = []
    const server = createServer((request: IncomingMessage, response) => {
      const chunks: Buffer[] = []
      request.on('data', (chunk: Buffer) => chunks.push(chunk))
      request.on('end', () => {
        got.push({
          method: request.method!,
          url: request.url!,
          auth: String(request.headers.authorization),
          body: Buffer.concat(chunks),
        })
        response.writeHead(200).end()
      })
    })
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    const port = (server.address() as { port: number }).port
    try {
      const outcome = await run(
        [
          'publish',
          project,
          '--to',
          's3://packs/projects',
          '--endpoint',
          `http://127.0.0.1:${port}`,
          '--public-url',
          'https://cdn.example.com/projects/',
        ],
        {
          ...env,
          AWS_ACCESS_KEY_ID: 'AKIDEXAMPLE',
          AWS_SECRET_ACCESS_KEY: 'secret',
          AWS_REGION: 'auto',
        },
      )
      expect(outcome.code).toBe(0)
      expect(got.map((it) => `${it.method} ${it.url}`)).toEqual([
        'PUT /packs/projects/showcase-0.1.0.nfbundle',
        'PUT /packs/projects/showcase-0.1.0.json',
      ])
      expect(got[0]!.auth).toMatch(
        /^AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE\/\d{8}\/auto\/s3\/aws4_request, SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature=[0-9a-f]{64}$/,
      )
      const listing = JSON.parse(got[1]!.body.toString()) as {
        sha256: string
        url: string
        version: string
      }
      expect(listing.url).toBe('https://cdn.example.com/projects/showcase-0.1.0.nfbundle')
      expect(outcome.out.at(-1)).toBe(`project: ${listing.url}#sha256=${listing.sha256}`)
    } finally {
      server.close()
    }

    // With the game's data and --packs-to: the bundle's resource pack, where servers' resource-pack.storage looks.
    got.length = 0
    const game = path.join(project, '..', 'game-data.json')
    writeFileSync(
      game,
      JSON.stringify({ minecraft: '26.3', packFormat: [75, 0], schema: GAME_DATA_SCHEMA }),
    )
    const again = createServer((request: IncomingMessage, response) => {
      got.push({ method: request.method!, url: request.url!, auth: '', body: Buffer.alloc(0) })
      request.resume()
      request.on('end', () => response.writeHead(200).end())
    })
    await new Promise<void>((resolve) => again.listen(0, '127.0.0.1', resolve))
    try {
      const port = (again.address() as { port: number }).port
      const outcome = await run(
        [
          'publish',
          project,
          '--to',
          's3://bundles',
          '--packs-to',
          's3://packs/rp/',
          '--game-data',
          game,
        ].concat(['--endpoint', `http://127.0.0.1:${port}`]),
        {
          ...env,
          AWS_ACCESS_KEY_ID: 'AKIDEXAMPLE',
          AWS_SECRET_ACCESS_KEY: 'secret',
          AWS_REGION: 'auto',
        },
      )
      expect(outcome.code).toBe(0)
      expect(got[0]!.url).toMatch(/^\/packs\/rp\/[0-9a-f]{40}\.zip$/)
    } finally {
      again.close()
    }

    const missing = await run(['publish', project, '--to', 's3://packs'], env)
    expect(missing.err.join('\n')).toContain('AWS_ACCESS_KEY_ID')
  })
})
