/**
 * Bundles beyond building them: signing (`keygen`, `build --sign`, `sign`), one file to fetch (`build --archive`, a
 * `.nfbundle`), and publishing to S3-compatible storage (`publish`), which a server then runs by URL
 * (`project: https://…/x.nfbundle#sha256=…`). A signature is Ed25519 over the bundle's manifest as it's written
 * without its signatures (format's `BundleManifest.signedText`), so a countersignature only adds one.
 */
import {
  createHash,
  createHmac,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  sign as signBytes,
} from 'node:crypto'
import { chmodSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { crc32, deflateRawSync } from 'node:zlib'
import * as nf from '@netherforge/format'
import { BUNDLE_FILE } from '@netherforge/format/constants'
import type { CanonicalResult } from '@netherforge/format/types'
import { listFiles } from './project.ts'

export interface Signature {
  key: string
  signature: string
}

interface Manifest {
  signatures?: Signature[] | null
  [key: string]: unknown
}

/** A raw Ed25519 public key as servers list it: `ed25519:` and its 32 bytes in base64. */
function publicOf(privateKeyPem: string): string {
  const der = createPublicKey(createPrivateKey(privateKeyPem)).export({
    type: 'spki',
    format: 'der',
  })
  return 'ed25519:' + Buffer.from(der.subarray(der.length - 32)).toString('base64')
}

/** Makes a key pair: the private key in [file] (PEM, readable by its owner alone), the public one given back. */
export function keygen(file: string): string {
  const { privateKey } = generateKeyPairSync('ed25519')
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString()
  writeFileSync(file, pem, { mode: 0o600 })
  chmodSync(file, 0o600)
  const key = publicOf(pem)
  writeFileSync(`${file}.pub`, key + '\n')
  return key
}

/** The manifest's text as format writes it (canonical), [signatures] and all. */
function write(manifest: Manifest): string {
  const result = JSON.parse(
    nf.canonicalize('bundle', BUNDLE_FILE, JSON.stringify(manifest)),
  ) as CanonicalResult
  if (result.text == null) throw new Error(`${BUNDLE_FILE} isn't a bundle's manifest`)
  return result.text
}

/**
 * Signs the bundle in [dir] with the private key in [keyFile]: its signature added to the manifest's (one by the
 * same key replaced). Gives the signing key.
 */
export function signBundle(dir: string, keyFile: string): string {
  const file = path.join(dir, BUNDLE_FILE)
  const manifest = JSON.parse(readFileSync(file, 'utf8')) as Manifest
  const pem = readFileSync(keyFile, 'utf8')
  const key = publicOf(pem)
  const { signatures, ...unsigned } = manifest
  const text = write({ ...unsigned })
  const signature = signBytes(null, Buffer.from(text, 'utf8'), createPrivateKey(pem)).toString(
    'base64',
  )
  const others = (signatures ?? []).filter((it) => it.key !== key)
  writeFileSync(file, write({ ...unsigned, signatures: [...others, { key, signature }] }))
  return key
}

/**
 * The bundle in [dir] as one zip file ([out]): every file, in path order, with no times or owners, so the same
 * bundle always makes the same bytes (and the same SHA-256). Gives its SHA-256, in hex.
 */
export function archive(dir: string, out: string): string {
  const files = listFiles(dir).sort()
  const local: Buffer[] = []
  const central: Buffer[] = []
  let offset = 0
  for (const name of files) {
    const data = readFileSync(path.join(dir, name))
    const packed = deflateRawSync(data, { level: 9 })
    const nameBytes = Buffer.from(name, 'utf8')
    const crc = crc32(data)
    const header = Buffer.alloc(30)
    header.writeUInt32LE(0x04034b50, 0)
    header.writeUInt16LE(20, 4) // version needed
    header.writeUInt16LE(0x0800, 6) // UTF-8 names
    header.writeUInt16LE(8, 8) // deflate
    header.writeUInt32LE(0, 10) // no time or date
    header.writeUInt32LE(crc, 14)
    header.writeUInt32LE(packed.length, 18)
    header.writeUInt32LE(data.length, 22)
    header.writeUInt16LE(nameBytes.length, 26)
    header.writeUInt16LE(0, 28)
    local.push(header, nameBytes, packed)
    const entry = Buffer.alloc(46)
    entry.writeUInt32LE(0x02014b50, 0)
    entry.writeUInt16LE(20, 4)
    entry.writeUInt16LE(20, 6)
    entry.writeUInt16LE(0x0800, 8)
    entry.writeUInt16LE(8, 10)
    entry.writeUInt32LE(0, 12)
    entry.writeUInt32LE(crc, 16)
    entry.writeUInt32LE(packed.length, 20)
    entry.writeUInt32LE(data.length, 24)
    entry.writeUInt16LE(nameBytes.length, 28)
    entry.writeUInt32LE(offset, 42)
    central.push(entry, nameBytes)
    offset += header.length + nameBytes.length + packed.length
  }
  const directory = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(files.length, 8)
  end.writeUInt16LE(files.length, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  const bytes = Buffer.concat([...local, directory, end])
  writeFileSync(out, bytes)
  return createHash('sha256').update(bytes).digest('hex')
}

/** Where `publish` puts things: `s3://bucket/prefix`, the endpoint (any S3-compatible one), and its region. */
export interface Storage {
  bucket: string
  prefix: string
  endpoint: string
  region: string
  accessKey: string
  secretKey: string
  sessionToken?: string
}

/** [to] (`s3://bucket/prefix`) and the environment's credentials as a [Storage]; throws saying what's missing. */
export function storage(to: string, endpoint: string | undefined, env: NodeJS.ProcessEnv): Storage {
  const match = /^s3:\/\/([^/]+)\/?(.*)$/.exec(to)
  if (!match) throw new Error(`--to ${to} isn't s3://bucket/prefix`)
  const accessKey = env.AWS_ACCESS_KEY_ID
  const secretKey = env.AWS_SECRET_ACCESS_KEY
  if (!accessKey || !secretKey)
    throw new Error(
      'publishing needs AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY in the environment',
    )
  const region = env.AWS_REGION ?? (endpoint ? 'auto' : 'us-east-1')
  return {
    bucket: match[1]!,
    prefix: match[2]!.replace(/\/+$/, ''),
    endpoint: (endpoint ?? `https://s3.${region}.amazonaws.com`).replace(/\/+$/, ''),
    region,
    accessKey,
    secretKey,
    sessionToken: env.AWS_SESSION_TOKEN,
  }
}

const sha256 = (data: Buffer | string) => createHash('sha256').update(data).digest('hex')
const hmac = (key: Buffer | string, data: string) => createHmac('sha256', key).update(data).digest()

/** A path's segments as S3 signs them: each byte outside the unreserved set percent-encoded. */
function encodePath(key: string): string {
  return key
    .split('/')
    .map((part) =>
      encodeURIComponent(part).replace(
        /[!'()*]/g,
        (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase(),
      ),
    )
    .join('/')
}

/**
 * PUTs [body] at [key] of [store], signed with AWS Signature Version 4 (path-style: `<endpoint>/<bucket>/<key>`), as
 * every S3-compatible store takes it. [now] is for tests.
 */
export async function put(
  store: Storage,
  key: string,
  body: Buffer,
  contentType: string,
  now = new Date(),
): Promise<void> {
  const url = new URL(`${store.endpoint}/${store.bucket}/${encodePath(key)}`)
  const stamp = now
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d{3}/, '')
  const day = stamp.slice(0, 8)
  const hash = sha256(body)
  const headers: Record<string, string> = {
    'content-type': contentType,
    host: url.host,
    'x-amz-content-sha256': hash,
    'x-amz-date': stamp,
  }
  if (store.sessionToken) headers['x-amz-security-token'] = store.sessionToken
  const names = Object.keys(headers).sort()
  const canonical = [
    'PUT',
    url.pathname,
    '',
    ...names.map((it) => `${it}:${headers[it]}`),
    '',
    names.join(';'),
    hash,
  ].join('\n')
  const scope = `${day}/${store.region}/s3/aws4_request`
  const toSign = ['AWS4-HMAC-SHA256', stamp, scope, sha256(canonical)].join('\n')
  let signingKey = hmac(`AWS4${store.secretKey}`, day)
  for (const part of [store.region, 's3', 'aws4_request']) signingKey = hmac(signingKey, part)
  const signature = createHmac('sha256', signingKey).update(toSign).digest('hex')
  headers.authorization = `AWS4-HMAC-SHA256 Credential=${store.accessKey}/${scope}, SignedHeaders=${names.join(';')}, Signature=${signature}`
  const { host, ...sent } = headers
  void host
  const response = await fetch(url, { method: 'PUT', headers: sent, body })
  if (!response.ok)
    throw new Error(
      `the store answered ${response.status} for ${key}: ${(await response.text()).slice(0, 300)}`,
    )
}
