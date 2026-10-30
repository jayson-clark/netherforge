/**
 * The editor's typed view of `@netherforge/format`, the only authority on what a
 * project file means and how it's written.
 *
 * The Kotlin/JS exports speak JSON strings; this module parses them once and
 * gives the rest of the UI plain typed values. Nothing in the editor formats
 * project JSON itself: a save is always `JSON.stringify(model)` →
 * [canonicalize] → `writeText`.
 */
import * as nf from '@netherforge/format'
import { sha256Hex } from './sha256'
import type {
  Box,
  Problem as FormatProblem,
  CanonicalResult,
  ClassifiedPath,
  ColorKey,
  CutsceneResult,
  DisplayDef,
  EffectStepResult,
  FitResult,
  GameDataBundle,
  ImageInfo,
  LootPreview,
  NumberKey,
  GitFetched,
  GitFetchRequest,
  PackagesNeeded,
  PackageSource,
  ResourcePacksPreview,
  PoseResult,
  PathRole,
  ProjectOutline,
  ProjectValidation,
  RefKind,
  RefTarget,
  RefUse,
  SettingDef,
  SettingValueResult,
  StyledChar,
  StyledText,
  TextLayout,
  TextLine,
  Usages,
  WasmInfo,
  WorkspaceResult,
} from '@netherforge/format/types'
import type {
  DocumentKindId,
  DocumentResourceKindId,
  KindId,
  ScriptedKindId,
  TemplateKindId,
} from '@netherforge/format/constants'

export type {
  AfterAction,
  AnimationDef,
  AnimationInfo,
  AttributeModifierDef,
  AttributeOperation,
  AttributeSlot,
  Billboard,
  BooleanInput,
  Box,
  BoxShape,
  CanonicalResult,
  CentityFile,
  Channel,
  DefaultFontFile,
  ColorKey,
  CutsceneFailed,
  CutsceneFile,
  CutsceneResult,
  CutsceneShot,
  CutsceneTrail,
  Cue,
  RotationKey,
  Camera as CutsceneCamera,
  CooldownDef,
  UseEffectsDef,
  EquipSlot,
  EquipmentAssetDef,
  AdditionsSound,
  AmbientParticle,
  BiomeClimate,
  BiomeColors,
  BiomeFile,
  BiomeMusic,
  BiomeSounds,
  BiomeSpawn,
  DimensionTypeColors,
  DimensionTypeFile,
  DimensionTypeSky,
  LightRange,
  GrassModifier,
  MoodSound,
  SpawnCost,
  TemperatureModifier,
  BlockFile,
  GameBlockFile,
  BlockModelDef,
  BlockSounds,
  BlockTool,
  BlockCarrier,
  BlockRotation,
  SkyFile,
  ScreenFile,
  ScreenPlace,
  ScreenFacing,
  ScreenViewers,
  ScreenVideo,
  ScreenFontFile,
  ScreenFontType,
  VideoFile,
  Dither,
  VideoFit,
  BuffFile,
  BuffStacking,
  EquipmentDef,
  Curves,
  DialogBody,
  DialogButton,
  DialogFile,
  DialogInput,
  DialogOption,
  DialogType,
  DisplayDef,
  DiscShape,
  Distribution,
  DustData,
  DustTransitionData,
  Easing,
  EffectSpawn,
  EffectStepFailed,
  EffectStepResult,
  EffectStepped,
  EmitterDef,
  EmitterShape,
  FitResult,
  FoodDef,
  GameDataBundle,
  GlyphDef,
  GlyphPreview,
  HitboxDef,
  HoldDef,
  HitboxShape,
  ImageInfo,
  MenuFile,
  MenuType,
  ItemBody,
  ItemDef,
  ItemFile,
  ItemModelDef,
  ItemModelParent,
  ItemRarity,
  TooltipComponent,
  UseAnimation,
  ItemTransform,
  Keyframe,
  LineShape,
  Location,
  MessageBody,
  Motion,
  NodeDef,
  NumberKey,
  NumberRangeInput,
  ResourcePackOutline,
  PackageOutline,
  ResourcePackPreview,
  ResourcePackFile,
  PackMeta,
  PackOverlay,
  ResourcePacksPreview,
  ParticleDataKind,
  ParticleEffectFile,
  PhysicsDef,
  PhysicsShape,
  BodyDef,
  HealthDef,
  HealthBarDef,
  RopeDef,
  HudDef,
  HudBarDef,
  HudIconsDef,
  HudTextDef,
  HudElement,
  HudAlign,
  PoolProjection,
  StructurePool,
  PoolElement,
  PointShape,
  Posed,
  PoseFailed,
  PoseResult,
  PosedNode,
  Problem,
  PathRole,
  ProjectOutline,
  ProjectValidation,
  ProjectManifest,
  SpawnCategory,
  MoonPhase,
  SpawnWeather,
  StructureGeneration,
  StructureHeightmap,
  TerrainAdaptation,
  GenerationStep,
  WorldConfig,
  RecipeCategory,
  RecipeFile,
  RecipeType,
  AdvancementFile,
  AdvancementDisplay,
  AdvancementIcon,
  AdvancementCriterion,
  AdvancementFrame,
  LootTableFile,
  LootPool,
  LootEntry,
  LootCondition,
  LootRange,
  LootPreview,
  LootPreviewDrop,
  ItemEntry,
  TableEntry,
  VanillaEntry,
  EmptyEntry,
  ChanceCondition,
  PlayerCondition,
  ToolCondition,
  EnchantmentCondition,
  RefKind,
  RefTarget,
  RefUse,
  RingShape,
  ScriptDef,
  BooleanSetting,
  ChoiceSetting,
  IntegerSetting,
  NumberSetting,
  StringSetting,
  SettingDef,
  SettingState,
  SettingValueResult,
  PackageSettings,
  ServerSettings,
  SingleOptionInput,
  SkinPreview,
  SkinDef,
  SlotDef,
  SoundDef,
  SpawnData,
  SphereShape,
  SpawnRange,
  SpawnTime,
  SpawningDef,
  StyledChar,
  StyledOverlay,
  TextAlignment,
  TextDisplay,
  TextInput,
  TextLayout,
  TextLine,
  TextStyle,
  TooltipDef,
  Transform,
  Vec3,
  TerrainFile,
  TerrainFailed,
  TerrainMap,
  TerrainPaletteEntry,
  TerrainPreviewResult,
  TerrainScript,
  TerrainScriptError,
  TerrainSlice,
  Terrain,
  TerrainNoise,
  NoiseDef,
  Layer,
  Floor,
  Cave,
  Ore,
  Climate,
  CaveType,
  NoiseFractal,
  NoiseType,
  OreDistribution,
  BiomeArea,
  ClimateRange,
  BlockRange,
  StructureRules,
  Stone,
  AreaTerrain,
  AreaDensity,
  Density,
  DensityNoise,
  Islands,
  Jitter,
  Decoration,
  DecorationPlacement,
  TerrainStructureInput,
  WasmExport,
  WasmImport,
  WasmInfo,
  WorkspaceFile,
  WorkspaceNetwork,
  WorkspaceResult,
} from '@netherforge/format/types'

// Defaults, limits, naming rules and where each kind lives, generated from
// the Kotlin declarations that own them.
export * from '@netherforge/format/constants'
export {
  AdvancementFrameValues,
  AnchorValues,
  AttributeOperationValues,
  AttributeSlotValues,
  BillboardValues,
  BlockToolValues,
  DialogTypeValues,
  DistributionValues,
  EasingValues,
  MenuTypeValues,
  ItemModelParentValues,
  EquipSlotValues,
  ItemRarityValues,
  TooltipComponentValues,
  UseAnimationValues,
  ItemTransformValues,
  MotionValues,
  ParticleDataKindValues,
  PhysicsShapeValues,
  RecipeCategoryValues,
  RecipeTypeValues,
  RefKindValues,
  SpawnCategoryValues,
  MoonPhaseValues,
  SpawnWeatherValues,
  StructureHeightmapValues,
  PoolProjectionValues,
  HudElementValues,
  HudAlignValues,
  TerrainAdaptationValues,
  GenerationStepValues,
  GrassModifierValues,
  TemperatureModifierValues,
  TextAlignmentValues,
  CaveTypeValues,
  NoiseFractalValues,
  NoiseTypeValues,
  OreDistributionValues,
  DecorationPlacementValues,
  DimensionTypeSkyValues,
} from '@netherforge/format/types'

/** Every resource kind the editor edits in an editor of its own: those whose resources are JSON. */
export type EditedKind = DocumentResourceKindId

/** [classify]'s answer, with format's ids as the kind table's types. */
export interface PathInfo {
  /** The resource kind; absent for a project file (`role: 'project'`). */
  kind?: KindId
  id?: string
  role: PathRole
  /** The document kind format reads the file as, when it's one. */
  document?: DocumentKindId
  /** For a folder resource: the path inside its folder (empty for the folder itself). */
  rest?: string
  /** For a package path (`library:items/gem/item.json`): the dependency it's in. */
  package?: string
  /** The group folders it's in (`(weapons)/(swords)`), absent when it's in its kind's folder itself. */
  group?: string
}

/** Answers by path: the editor asks about the same few hundred paths on every render. */
const classified = new Map<string, PathInfo | null>()

/**
 * What a project path is (format's `Kinds.classify`): the resource it belongs
 * to and what part of it, or a project file format owns (the manifest, the
 * default font). Null for anything else.
 */
export function classify(path: string): PathInfo | null {
  let found = classified.get(path)
  if (found === undefined) {
    found = JSON.parse(nf.classify(path)) as (ClassifiedPath & PathInfo) | null
    // Past what a large project has, so a pass over every file never empties it partway.
    if (classified.size > 200_000) classified.clear()
    classified.set(path, found)
  }
  return found
}

/** Which document kind a project path is, or null for files format doesn't own (Lua, images). */
export const documentKindOf = (path: string): DocumentKindId | null =>
  classify(path)?.document ?? null

export function canonicalize(kind: DocumentKindId, path: string, text: string): CanonicalResult {
  return JSON.parse(nf.canonicalize(kind, path, text)) as CanonicalResult
}

/** The canonical text of an in-memory model: the one way the editor produces file contents. */
/**
 * A workspace file read and checked against [projects], the project folders under it (relative):
 * the file (null when it doesn't parse), the folders it names, and its problems.
 */
export function checkWorkspace(text: string, projects: string[]): WorkspaceResult {
  return JSON.parse(nf.checkWorkspace(text, JSON.stringify(projects))) as WorkspaceResult
}

/** A name a script gives `nf.network` as a literal: a server it sends players to, a channel or a shared map (format's `NetworkUse`). */
export interface NetworkUse {
  kind: 'server' | 'channel' | 'network_map'
  name: string
  line: number
}

/** The network names a Lua script uses as literals. */
export function networkUses(source: string): NetworkUse[] {
  return JSON.parse(nf.networkUses(source)) as NetworkUse[]
}

/** What's wrong with [scripts]' (Lua source by path) network names, given the workspace network's [servers]. */
export function checkNetworkUses(
  scripts: Record<string, string>,
  servers: string[],
): FormatProblem[] {
  return JSON.parse(
    nf.checkNetworkUses(JSON.stringify(scripts), JSON.stringify(servers)),
  ) as FormatProblem[]
}

/** Whether [name] can name a server of a workspace's dev network. */
export function isWorkspaceServerName(name: string): boolean {
  return nf.isWorkspaceServerName(name)
}

export function canonicalizeModel(
  kind: DocumentKindId,
  path: string,
  model: unknown,
): CanonicalResult {
  return canonicalize(kind, path, JSON.stringify(model))
}

/**
 * A package folder the project's dependencies point at, as read: its files
 * (`{ path: text | null }`, as [loadProject] takes the project's) and its
 * content hash ([packageHash]). Packages are keyed by location: relative to
 * the project (`../library`), or `git:<commit>` for a git package's checkout
 * in the package cache; null means there's nothing there.
 */
export interface PackageFolder {
  files: Record<string, string | null>
  hash: string | null
}

export type PackageFolders = Record<string, PackageFolder | null>

/** Git dependencies fetched into the package cache, by their request's key ([GitFetchRequest]). */
export type GitFetches = Record<string, GitFetched>

/** What the host read and fetched of a project's packages, as [packagesNeeded] asked: format's `PackageInputs`. */
export interface PackageInputs {
  folders: PackageFolders
  git: GitFetches
}

/** PNGs' `[width, height]` in pixels, by project path. */
export type ImageSizes = Record<string, [number, number]>

export const NO_PACKAGES: PackageInputs = { folders: {}, git: {} }

export type { GitFetchRequest, PackageSource }

/**
 * Validates a whole project. [files] maps every project path to its text, or
 * to null when its contents don't matter to validation (`.lua`, images);
 * [packages] are the package folders its dependencies point at, read as
 * [packagesNeeded] asks. [sizes] are the `[width, height]` of the PNGs the
 * host measured, by path, which hold a resource pack's `textures` to the game's sizes.
 */
export function loadProject(
  files: Record<string, string | null>,
  gameData: GameDataBundle | null,
  packages: PackageInputs = NO_PACKAGES,
  sizes: ImageSizes | null = null,
): ProjectOutline {
  const raw = nf.loadProject(
    JSON.stringify(files),
    JSON.stringify(packages),
    gameData ? JSON.stringify(gameData) : null,
    sizes ? JSON.stringify(sizes) : null,
    // The editor reads no module: the dev server reports what its modules import and export.
    null,
  )
  return JSON.parse(raw) as ProjectOutline
}

/**
 * An open project validated again and again as it changes (format's
 * `ProjectValidator`): it keeps the project's files and its packages', told
 * only what changed, and what each file validated to, so [validate] after an
 * edit validates only the files that changed, then checks everything across
 * files again. The validation worker holds one per open project.
 */
export class ProjectValidator {
  private readonly validator = new nf.ProjectValidator()

  /** The pictures' sizes (see [loadProject]), all of them: what was set before is forgotten. */
  setSizes(sizes: ImageSizes | null): void {
    this.validator.setSizes(sizes ? JSON.stringify(sizes) : null)
  }

  /** Sets a file's text: null for one whose contents format doesn't read (`.lua`, `.png`). */
  setFile(path: string, text: string | null): void {
    this.validator.setFile(path, text)
  }

  deleteFile(path: string): void {
    this.validator.deleteFile(path)
  }

  /** The game data ids are checked against, or null before an import. */
  setGameData(gameData: GameDataBundle | null): void {
    this.validator.setGameData(gameData ? JSON.stringify(gameData) : null)
  }

  /** The packages the dependencies resolve to, as [loadProject] takes them. */
  setPackages(packages: PackageInputs): void {
    this.validator.setPackages(JSON.stringify(packages))
  }

  /** The project as it is now, and the files validated afresh to see it (a package's at package paths). */
  validate(): ProjectValidation {
    return JSON.parse(this.validator.validate()) as ProjectValidation
  }
}

/**
 * What resolving the project's dependencies still needs, given [packages]:
 * package folders to read (by location) and git dependencies to fetch into
 * the package cache. Ask again once they're there, since a package's own
 * dependencies are only known then. Git dependencies are asked for at the
 * commits [files]' `netherforge.lock` pins: leave it out to resolve afresh.
 */
export function packagesNeeded(
  files: Record<string, string | null>,
  packages: PackageInputs,
): PackagesNeeded {
  return JSON.parse(
    nf.packagesNeeded(JSON.stringify(files), JSON.stringify(packages)),
  ) as PackagesNeeded
}

/**
 * A package's content hash (`sha256:…`) from each file's hex SHA-256 by path:
 * format says which files and how they're listed, the webview hashes.
 */
export async function packageHash(digests: Record<string, string>): Promise<string> {
  return nf.packageHash(await sha256Hex(nf.packageListing(JSON.stringify(digests))))
}

/** Whether [path], inside a package, is one of its files: what it is (hashed) and its default font (read, never hashed). */
export const isPackageFile = (path: string): boolean => nf.isPackageBundled(path)

/**
 * Document [text] (the file [path] of the resource [target] of package
 * [from]) as it must read once copied into the project [to] as [id]: the
 * package's own things named `from:…`, the resource itself as the copy.
 * Canonical; null when it isn't a document format reads or doesn't parse.
 */
export const moveRefs = (
  path: string,
  text: string,
  from: string,
  to: string,
  target: RefTarget,
  id: string,
): string | null => nf.moveRefs(path, text, from, to, JSON.stringify(target), id) ?? null

/**
 * Document [text] (the file [path], written in namespace [namespace]) with
 * every reference in it written in full (`gems/gem` is `library:gems/gem`):
 * how a package's resource is drawn beside the project's. Null when [path]
 * isn't a document format reads, or [text] isn't JSON.
 */
export const qualifyRefs = (path: string, text: string, namespace: string): string | null =>
  nf.qualifyRefs(path, text, namespace) ?? null

/**
 * Every reference to [target] in the project (`{ path: text | null }`, as
 * [loadProject] takes it), from outside it: what deleting it would break.
 */
/**
 * What a WebAssembly module's bytes say about it, by format's own reader: what it imports and exports, its memory,
 * or why it isn't a module format can read. The server checks the rest as it compiles it.
 */
export function readWasm(bytes: Uint8Array): WasmInfo {
  return JSON.parse(
    nf.readWasm(new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)),
  ) as WasmInfo
}

export function findUsages(files: Record<string, string | null>, target: RefTarget): RefUse[] {
  return (JSON.parse(nf.findUsages(JSON.stringify(files), JSON.stringify(target))) as Usages).uses
}

/**
 * Document [text] (the project file [path]) with every reference to [target]
 * renamed to [to] (its new id or key, or a file's new project path),
 * canonical; null when nothing in it names the target. [namespace] is the
 * project's. Every rename the editor follows goes through this one rule.
 */
export const renameRefs = (
  path: string,
  text: string,
  namespace: string,
  target: RefTarget,
  to: string,
): string | null => nf.renameRefs(path, text, namespace, JSON.stringify(target), to) ?? null

/**
 * [text], a reference of [kind] written in a project whose namespace is
 * [namespace], as the key it names: `ui/coin` in `shop` is `shop:ui/coin`.
 * Null when it isn't shaped like one.
 */
export const resolveReference = (kind: RefKind, text: string, namespace: string): string | null =>
  nf.resolveReference(kind, text, namespace) ?? null

/** A centity compiled once and posed as often as needed (see [poseCentity]). */
export interface CentityPoser {
  pose(animation: string | null, time: number): PoseResult
}

/** Parses, validates and compiles a centity once: a viewport playing a clip poses it every frame. */
export function centityPoser(id: string, text: string): CentityPoser {
  const poser = nf.centityPoser(id, text)
  return {
    pose: (animation, time) => JSON.parse(poser.pose(animation, time)) as PoseResult,
  }
}

/**
 * World matrices for every node of a centity, posed by [animation] at [time]
 * seconds (or the base pose when [animation] is null). The editor never
 * composes transforms itself; this is the same code the server runs.
 */
export function poseCentity(
  id: string,
  text: string,
  animation: string | null,
  time: number,
): PoseResult {
  return JSON.parse(nf.poseCentity(id, text, animation, time)) as PoseResult
}

/** A cutscene compiled once and asked where its camera is at any time (see [cutsceneDirector]). */
export interface CutsceneDirector {
  /** The camera at [time] seconds, held at the first and last keys outside the keyed range. */
  shot(time: number): CutsceneResult
  /** The camera's position at [count] evenly spaced times from 0 to the end. */
  trail(count: number): CutsceneResult
}

/**
 * The server's own sampling of a cutscene's text: where the camera is and
 * which way it looks, so the preview shows the shot the player sees.
 */
export function cutsceneDirector(id: string, text: string): CutsceneDirector {
  const director = nf.cutsceneDirector(id, text)
  return {
    shot: (time) => JSON.parse(director.shot(time)) as CutsceneResult,
    trail: (count) => JSON.parse(director.trail(count)) as CutsceneResult,
  }
}

// The generator preview is shared with `netherforge preview`.
export { terrainPreviewer, type TerrainPreviewer } from '@netherforge/terrain-preview'

/** A particle effect compiled once and stepped tick by tick (see [particleEffectSampler]). */
export interface ParticleEffectSampler {
  /** The current tick's spawns (effect space), then advances; wraps with [loop]. */
  step(loop: boolean): EffectStepResult
  /** Back to tick 0 with the same seed: a replay draws the same points. */
  reset(): void
}

/**
 * The server's own sampler for an effect's text: what each tick spawns, so
 * the preview draws the points the server would send. [seed] fixes the
 * randomness (the preview uses 1, so it doesn't shimmer between edits).
 */
export function particleEffectSampler(
  id: string,
  text: string,
  seed: number,
): ParticleEffectSampler {
  const sampler = nf.particleEffectSampler(id, text, seed)
  return {
    step: (loop) => JSON.parse(sampler.step(loop)) as EffectStepResult,
    reset: () => sampler.reset(),
  }
}

/** A curve channel's value at [tick] as the sampler reads it, or null with no keys. */
export function particleCurveAt(keys: NumberKey[], tick: number): number | null
export function particleCurveAt(keys: ColorKey[], tick: number): string | null
export function particleCurveAt(
  keys: NumberKey[] | ColorKey[],
  tick: number,
): number | string | null {
  const color = keys.length > 0 && 'color' in keys[0]!
  return JSON.parse(nf.particleCurveAt(JSON.stringify(keys), color, tick)) as number | string | null
}

type Files = Record<string, string>

/** Files for a new, empty project, already canonical: `{ path: text }`. */
export const newProjectFiles = (name: string, minecraft: string) =>
  JSON.parse(nf.newProjectFiles(name, minecraft)) as Files

/** A project's AGENTS.md and CLAUDE.md, pointing coding agents at `.netherforge/docs/`. */
export const newAgentFiles = (name: string) => JSON.parse(nf.newAgentFiles(name)) as Files

/**
 * Files for a new resource of [kind], already canonical, from format's template for it, given the server's
 * [gameData] (a datapack's new `pack.mcmeta` names its format), in [group] folders when it's given (`(weapons)`);
 * `needsGame` says why there are none when a kind's template needs game data there isn't.
 */
export const newResourceFiles = (
  kind: TemplateKindId,
  id: string,
  gameData: GameDataBundle | null,
  group = '',
): { files: Files } | { needsGame: string } =>
  JSON.parse(
    nf.newResourceFiles(kind, id, gameData ? JSON.stringify(gameData) : null, group || null),
  ) as { files: Files } | { needsGame: string }

/**
 * Why a project can't be opened, from its `netherforge.json` text: a format
 * version other than this build's, named in the message. Null when it can.
 */
export const projectRefusal = (manifestText: string): string | null =>
  nf.projectRefusal(manifestText) ?? null

/** What a new script of a [kind] whose resources run Lua starts as; [id] is the resource's (a module names itself). */
export const newScript = (kind: ScriptedKindId, id: string | number) =>
  nf.newScript(kind, String(id))

/** What a new Lua file beside a [kind]'s script (one the script requires) starts as. */
export const newSiblingFile = (kind: ScriptedKindId) => nf.newSiblingFile(kind)

/** What a new terrain script (`terrain/<id>.lua`, its file's `script`) starts as. */
export const newTerrainScript = () => nf.newTerrainScript()

/**
 * Every pack's skins and glyphs exactly as the server will use them, from
 * `{ pack id: pack.json text }` in a project whose namespace is [namespace],
 * and the pixel facts of their pictures by project path
 * (`resource_packs/ui/textures/gui/shop.png`; a picture left out gets no advance).
 * ResourcePacks that don't parse make the whole result empty, with their problems.
 */
export function compileResourcePacks(
  namespace: string,
  resourcePackTexts: Record<string, string>,
  images: Record<string, ImageInfo> = {},
): ResourcePacksPreview {
  return JSON.parse(
    nf.compileResourcePacks(namespace, JSON.stringify(resourcePackTexts), JSON.stringify(images)),
  ) as ResourcePacksPreview
}

/** Advance tables as JSON, built once per table (they're passed on every measure). */
const tableJsons = new WeakMap<Record<string, number>, string>()

function tableJson(table: Record<string, number> | null): string | null {
  if (!table) return null
  let json = tableJsons.get(table)
  if (json === undefined) {
    json = JSON.stringify(table)
    tableJsons.set(table, json)
  }
  return json
}

/**
 * The hitbox that fits a node's display when it's a `fixed` text display, or
 * null for anything else (format's `TextMetrics`, measured with the default
 * font's [advances] by code point and pack [glyphs]' advances by reference).
 */
export function fitTextHitbox(
  display: DisplayDef,
  advances: Record<string, number> | null,
  glyphs: Record<string, number> | null = null,
): Box | null {
  const raw = nf.fitTextHitbox(JSON.stringify(display), tableJson(advances), tableJson(glyphs))
  return (JSON.parse(raw) as FitResult).box ?? null
}

/**
 * MiniMessage laid out as the game does, measured with the default font's
 * [advances] (from the client import; unknown glyphs are estimated) and pack
 * [glyphs]' advances by reference (`{ 'ui/coin': 9 }`, for `<glyph:…>` tags),
 * wrapped at [lineWidth] pixels (0: never).
 */
export function layoutText(
  text: string,
  lineWidth: number,
  advances: Record<string, number> | null,
  glyphs: Record<string, number> | null = null,
): TextLine[] {
  const raw = nf.layoutText(
    text,
    Math.max(0, Math.round(lineWidth)),
    tableJson(advances),
    tableJson(glyphs),
  )
  return (JSON.parse(raw) as TextLayout).lines
}

/** Answers by text: previews ask about the same names, titles and lore on every render. */
const styled = new Map<string, StyledChar[]>()

/**
 * MiniMessage [text] as the characters a preview draws (format's one pass, the
 * one [layoutText] measures): each with its style (only what tags set) and its
 * index in [text], a `<glyph:…>` tag as one character with `glyph` set and no
 * `char`. Line breaks and other tags draw nothing.
 */
export function styleText(text: string): StyledChar[] {
  let found = styled.get(text)
  if (found === undefined) {
    found = (JSON.parse(nf.styleText(text)) as StyledText).chars
    if (styled.size > 10_000) styled.clear()
    styled.set(text, found)
  }
  return found
}

/**
 * One roll of loot table [table] (its full name: `showcase:treasure`,
 * `library:gems`), as the server would roll it with no player, tool or luck,
 * from [seed] (format's roller): [tables] is every loot table's text, the
 * project's and its packages', by full name, for the ones it includes. What
 * it gives names items in full (`library:gem`).
 */
export const rollLoot = (
  tables: Record<string, string>,
  table: string,
  seed: number,
): LootPreview => JSON.parse(nf.rollLoot(JSON.stringify(tables), table, seed)) as LootPreview

/**
 * A value for the server-owner setting [definition], read as the server
 * reads it (format's `SettingDef`): [input] is JSON, or words as typed when
 * [typed] (`12`, `true`, `hard`). The value in its one written form, or why
 * the setting can't have it, in the server's words.
 */
export const readSetting = (
  definition: SettingDef,
  input: string,
  typed: boolean,
): SettingValueResult =>
  JSON.parse(nf.readSetting(JSON.stringify(definition), input, typed)) as SettingValueResult

/** A block state's canonical text (namespaced, properties sorted), or null when [text] isn't one. */
export const canonicalBlockState = (text: string): string | null =>
  nf.canonicalBlockState(text) ?? null

/** What a hitbox fitted to [display] records in `fittedTo`; null when nothing can be fitted to it. */
export const fitKey = (display: DisplayDef | undefined): string | null =>
  display ? (nf.fitKey(JSON.stringify(display)) ?? null) : null
