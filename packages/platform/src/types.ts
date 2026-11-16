/**
 * The Platform spec's shape: what the runtime asks of a server (groups of operations, like
 * `teams` or `worlds`), what the server tells the runtime (`PlatformEvents`), and the types
 * that cross. One definition, from which each side's code is generated (see the plugin-runtime
 * skill, "The Platform boundary").
 *
 * Types are strings in Kotlin's spelling, which is what the spec was first read from:
 * `String`, `Int`, `Long`, `Double`, `Float`, `Boolean`, `Unit`, `UUID`, `ByteArray`,
 * `IntArray`, `List<T>`, `Set<T>`, `Map<K, V>`, `Pair<A, B>`, `Triple<A, B, C>`, `Result<T>`,
 * `T?`, a function `(name: T) -> R`, a type this spec declares, or a type format declares
 * ([EXTERNAL]).
 */

export type PlatformType = string

export interface Param {
  name: string
  type: PlatformType
  /** Kotlin's default, as written: the argument may be left out. */
  default?: string
  doc?: string
}

export interface Method {
  name: string
  doc?: string
  params: Param[]
  returns: PlatformType
  /** It has a default body: an implementation may leave it out. */
  defaulted?: boolean
}

/** A value a group exposes (`val info: ServerInfo`), read by the other side. */
export interface Property {
  name: string
  doc?: string
  type: PlatformType
  /** It has a default (`get() = ...`): an implementation may leave it out. */
  defaulted?: boolean
}

/** A record of values: Kotlin's data class. */
export interface RecordDecl {
  kind: 'record'
  name: string
  doc?: string
  fields: Param[]
}

/** A fixed set of names, each maybe with values: Kotlin's enum class. */
export interface EnumDecl {
  kind: 'enum'
  name: string
  doc?: string
  /** What each entry carries (`settable: Boolean`). */
  params: Param[]
  entries: { name: string; doc?: string; args: string[] }[]
}

/** One of several records: Kotlin's sealed interface. A case without fields is an object. */
export interface UnionDecl {
  kind: 'union'
  name: string
  doc?: string
  cases: { name: string; doc?: string; fields: Param[] }[]
}

/**
 * Operations one side implements and the other calls: a group of the Platform (`TeamOps`),
 * or what the runtime hands the server to call back (`CommandHandler`, `PlatformEvents`).
 * An `object` is a value with methods that the side making it implements (`CommandSender`:
 * its fields, and `reply`).
 */
export interface InterfaceDecl {
  kind: 'interface' | 'object'
  name: string
  doc?: string
  /** The interface it extends. */
  extends?: string
  /** An object's fields. */
  fields?: Param[]
  properties: Property[]
  methods: Method[]
}

export type Decl = RecordDecl | EnumDecl | UnionDecl | InterfaceDecl

/** One file of the spec: the declarations one Kotlin file held. */
export interface PlatformFile {
  /** The Kotlin file it was read from, in the runtime's `platform/` package. */
  source: string
  decls: Decl[]
}

/**
 * The types the boundary carries that this spec doesn't declare, by who does: format (its
 * package), the Lua API spec (`api`: generated for each language from `packages/api`, like the
 * server's raised events), or the runtime (`runtime:<package>`).
 */
export const EXTERNAL: Record<string, string> = {
  ArgumentType: 'api',
  GameEvents: 'api',
  WatchedEvent: 'api',
  'dev.netherforge.plugin.datapack.DatapackRefusal': 'runtime:datapack',
  'dev.netherforge.plugin.platform.terrain.TerrainSource': 'runtime:platform.terrain',
  BlockCarrier: 'block',
  Vec3: 'math',
  Matrix4: 'math',
  Box: 'game',
  GameData: 'game',
  GameDataBundle: 'game',
  GameIds: 'game',
  ItemDef: 'item',
  AttributeOperation: 'item',
  TerrainScripts: 'terrain',
  StructureTemplate: 'terrain',
  CompiledTerrain: 'terrain',
  ScreenFacing: 'screen',
  RecipeFile: 'recipe',
  SpawnCategory: 'project',
  SpawnData: 'particle',
  MenuType: 'menu',
  DialogFile: 'dialog',
  GameDatapack: 'datapack',
  DisplayDef: 'centity',
  Billboard: 'centity',
  BotState: 'bridge',
  BotPosition: 'bridge',
  BotPackAnswer: 'bridge',
  BotInfo: 'bridge',
  BotEvents: 'bridge',
  BotAction: 'bridge',
  BotActResult: 'bridge',
  Path: 'java.nio.file',
}
