/**
 * Every resource kind as the workbench shows it, and the tabs that aren't a
 * resource's own (a script, raw JSON, the project's settings). One entry per
 * kind in format's generated `KINDS`, so a kind without one is a type error,
 * and the explorer, tabs, editor area, outline, thumbnails, menus and
 * palette all read it: adding an editor is an entry here and its view.
 *
 * The entries' order is the explorer's, within each of [KIND_GROUPS].
 */
import { lazy } from 'react'
import { KINDS, type BinaryKindId, type EditedKind, type KindId } from '@/core/format'
import { basename, isFileKind, resourceOf } from '@/core/paths'
import { ADVANCEMENT_VIEW } from '@/editors/advancement/view'
import { AdvancementThumbnail } from '@/editors/advancement/AdvancementThumbnail'
import { BiomeThumbnail } from '@/editors/biome/BiomeThumbnail'
import { CutsceneThumbnail } from '@/editors/cutscene/CutsceneThumbnail'
import { DialogThumbnail } from '@/editors/dialog/DialogThumbnail'
import { GameBlockThumbnail } from '@/editors/game_block/GameBlockThumbnail'
import { LootThumbnail } from '@/editors/loot/LootThumbnail'
import { MenuThumbnail } from '@/editors/menu/MenuThumbnail'
import { ParticleThumbnail } from '@/editors/particles/ParticleThumbnail'
import { folderPicture } from '@/editors/shared/FolderPicture'
import { CENTITY_VIEW } from '@/editors/centity/view'
import { CUTSCENE_VIEW } from '@/editors/cutscene/view'
import { useThumbnails } from '@/editors/centity/thumbnails'
import { DIALOG_VIEW } from '@/editors/dialog/view'
import { BlockThumbnail } from '@/editors/block/BlockThumbnail'
import { ItemThumbnail } from '@/editors/item/ItemThumbnail'
import { LOOT_VIEW } from '@/editors/loot/view'
import { MENU_VIEW } from '@/editors/menu/view'
import { RESOURCE_PACK_VIEW } from '@/editors/resource_pack/view'
import { ResourcePackThumbnail } from '@/editors/resource_pack/ResourcePackThumbnail'
import { PARTICLE_VIEW } from '@/editors/particles/view'
import { RECIPE_VIEW } from '@/editors/recipe/view'
import { RecipeThumbnail } from '@/editors/recipe/RecipeThumbnail'
import { STRUCTURE_VIEW } from '@/editors/structure/view'
import { MAP_VIEW } from '@/editors/map/view'
import { TERRAIN_VIEW } from '@/editors/terrain/view'
import type {
  CommandContext,
  KindContribution,
  KindGroup,
  TabView,
  ViewStateSpec,
} from './contributions'

const bridgeUp = (c: CommandContext) => c.app.run.getState().server.bridgeConnected

/** A kind with a document or a binary screen must have a view; a kind that opens as a file (Lua, SQL) has none. */
type KindEntries = {
  [K in KindId]: KindContribution &
    ((typeof KINDS)[K]['contents'] extends 'lua' | 'sql'
      ? { view?: never }
      : Required<Pick<KindContribution, 'view'>>)
}

export const KIND_CONTRIBUTIONS = {
  centity: {
    title: 'Centities',
    one: 'centity',
    group: 'gameplay',
    icon: 'cube',
    tone: 'blue',
    message: 'Scripts refer to it by this id, e.g. nf.centities.spawn("tower", location).',
    view: lazy(() =>
      import('@/editors/centity/CentityEditor').then((m) => ({ default: m.CentityEditor })),
    ),
    outline: lazy(() =>
      import('@/editors/centity/CentityOutline').then((m) => ({ default: m.CentityOutline })),
    ),
    // three.js comes with the first centity picture, as it does with the first centity editor.
    thumbnail: lazy(() =>
      import('@/editors/centity/CentityThumbnail').then((m) => ({ default: m.CentityThumbnail })),
    ),
    studio: {
      subscribe: (listener) => useThumbnails.subscribe(listener),
      busy: () => useThumbnails.getState().queue.length > 0,
      view: lazy(() =>
        import('@/editors/centity/CentityThumbnail').then((m) => ({ default: m.ThumbnailStudio })),
      ),
    },
    viewState: CENTITY_VIEW,
    commands: [
      {
        scope: 'resource',
        name: 'spawn',
        label: (id) => (id ? `Spawn ${id} at Me` : 'Spawn at Me'),
        toolbar: 'Spawn at me',
        hint: 'Where you stand in the game on the dev server',
        icon: 'spawn',
        menu: 'run',
        when: bridgeUp,
        run: (c, id) => c.app.run.getState().spawn(id),
      },
    ],
  },
  item: {
    title: 'Items',
    one: 'item',
    group: 'gameplay',
    icon: 'gem',
    message: 'Anything that takes an item names it by this id, e.g. { "item": "ruby" }.',
    view: lazy(() => import('@/editors/item/ItemScreen').then((m) => ({ default: m.ItemScreen }))),
    thumbnail: ItemThumbnail,
  },
  block: {
    title: 'Blocks',
    one: 'block',
    group: 'gameplay',
    icon: 'block',
    tone: 'orange',
    message:
      'Items place it by this id ({ "block": "ruby_ore" }), and scripts reach it with nf.blocks.get("ruby_ore").',
    view: lazy(() =>
      import('@/editors/block/BlockEditor').then((m) => ({ default: m.BlockEditor })),
    ),
    thumbnail: BlockThumbnail,
  },
  game_block: {
    title: 'Game blocks',
    one: 'game block',
    group: 'gameplay',
    icon: 'blocks',
    message:
      "Only its name: it gives the game's blocks it lists, by id or #tag, a hardness, tool and drops of the project's.",
    view: lazy(() =>
      import('@/editors/game_block/GameBlockEditor').then((m) => ({
        default: m.GameBlockEditor,
      })),
    ),
    thumbnail: GameBlockThumbnail,
  },
  recipe: {
    title: 'Recipes',
    one: 'recipe',
    group: 'gameplay',
    icon: 'recipe',
    message:
      'Scripts and recipe books name it by this id, e.g. player:discover_recipe("ruby_sword").',
    view: lazy(() =>
      import('@/editors/recipe/RecipeEditor').then((m) => ({ default: m.RecipeEditor })),
    ),
    thumbnail: RecipeThumbnail,
    viewState: RECIPE_VIEW,
  },
  loot_table: {
    title: 'Loot tables',
    one: 'loot table',
    group: 'gameplay',
    icon: 'dice',
    tone: 'amber',
    message: 'Scripts roll it by this id, e.g. nf.loot.roll("treasure", { player = player }).',
    view: lazy(() => import('@/editors/loot/LootEditor').then((m) => ({ default: m.LootEditor }))),
    outline: lazy(() =>
      import('@/editors/loot/LootOutline').then((m) => ({ default: m.LootOutline })),
    ),
    viewState: LOOT_VIEW,
    thumbnail: LootThumbnail,
  },
  advancement: {
    title: 'Advancements',
    one: 'advancement',
    group: 'gameplay',
    icon: 'trophy',
    tone: 'green',
    message:
      'Scripts grant it by this id, e.g. player:grant_advancement("treasure_hunter"). Its parent makes the tree.',
    view: lazy(() =>
      import('@/editors/advancement/AdvancementEditor').then((m) => ({
        default: m.AdvancementEditor,
      })),
    ),
    outline: lazy(() =>
      import('@/editors/advancement/AdvancementOutline').then((m) => ({
        default: m.AdvancementOutline,
      })),
    ),
    viewState: ADVANCEMENT_VIEW,
    thumbnail: AdvancementThumbnail,
  },
  menu: {
    title: 'Menus',
    one: 'menu',
    group: 'interface',
    icon: 'slot',
    tone: 'purple',
    message: 'Scripts open it by this id, e.g. player:open_menu("shop").',
    view: lazy(() => import('@/editors/menu/MenuEditor').then((m) => ({ default: m.MenuEditor }))),
    outline: lazy(() =>
      import('@/editors/menu/MenuOutline').then((m) => ({ default: m.MenuOutline })),
    ),
    viewState: MENU_VIEW,
    thumbnail: MenuThumbnail,
  },
  dialog: {
    title: 'Dialogs',
    one: 'dialog',
    group: 'interface',
    icon: 'dialog',
    tone: 'purple',
    message: 'Scripts show it by this id, and dialog lists name it.',
    view: lazy(() =>
      import('@/editors/dialog/DialogEditor').then((m) => ({ default: m.DialogEditor })),
    ),
    outline: lazy(() =>
      import('@/editors/dialog/DialogOutline').then((m) => ({ default: m.DialogOutline })),
    ),
    viewState: DIALOG_VIEW,
    thumbnail: DialogThumbnail,
  },
  cutscene: {
    title: 'Cutscenes',
    one: 'cutscene',
    group: 'interface',
    icon: 'film',
    tone: 'cyan',
    message: 'Scripts play it by this id, e.g. nf.cutscenes.play(player, "intro").',
    view: lazy(() =>
      import('@/editors/cutscene/CutsceneEditor').then((m) => ({ default: m.CutsceneEditor })),
    ),
    viewState: CUTSCENE_VIEW,
    thumbnail: CutsceneThumbnail,
  },
  screen: {
    title: 'Screens',
    one: 'screen',
    group: 'interface',
    icon: 'screen',
    tone: 'green',
    message: 'Scripts reach it by this id, e.g. nf.screens.get("lobby_board").',
    view: lazy(() =>
      import('@/editors/screen/ScreenEditor').then((m) => ({ default: m.ScreenEditor })),
    ),
  },
  resource_pack: {
    title: 'Resource packs',
    one: 'resource pack',
    group: 'art',
    icon: 'image',
    tone: 'cyan',
    message: 'The id is its folder name: others refer to what\'s in it as "<id>/<key>".',
    view: lazy(() =>
      import('@/editors/resource_pack/ResourcePackEditor').then((m) => ({
        default: m.ResourcePackEditor,
      })),
    ),
    outline: lazy(() =>
      import('@/editors/resource_pack/ResourcePackOutline').then((m) => ({
        default: m.ResourcePackOutline,
      })),
    ),
    thumbnail: ResourcePackThumbnail,
    viewState: RESOURCE_PACK_VIEW,
  },
  screen_font: {
    title: 'Screen fonts',
    one: 'screen font',
    group: 'art',
    icon: 'font',
    tone: 'purple',
    message: 'Screens write in it by this id, e.g. screen:text(4, 4, "Hi", { font = "pixel" }).',
    view: lazy(() =>
      import('@/editors/screen_font/ScreenFontEditor').then((m) => ({
        default: m.ScreenFontEditor,
      })),
    ),
  },
  video: {
    title: 'Videos',
    one: 'video',
    group: 'art',
    icon: 'video',
    tone: 'amber',
    message: 'Screens play it by this id, e.g. screen:play("trailer").',
    view: lazy(() =>
      import('@/editors/video/VideoEditor').then((m) => ({ default: m.VideoEditor })),
    ),
  },
  particle_effect: {
    title: 'Particle effects',
    one: 'particle effect',
    group: 'art',
    icon: 'sparkles',
    tone: 'orange',
    message: 'Scripts play it by this id, e.g. nf.particles.play("sparkle", location).',
    view: lazy(() =>
      import('@/editors/particles/ParticleEditor').then((m) => ({ default: m.ParticleEditor })),
    ),
    outline: lazy(() =>
      import('@/editors/particles/ParticleOutline').then((m) => ({ default: m.ParticleOutline })),
    ),
    viewState: PARTICLE_VIEW,
    commands: [
      {
        scope: 'kind',
        name: 'stopAll',
        label: 'Stop Particle Effects',
        hint: 'Every effect the editor played on the dev server',
        icon: 'stop',
        when: bridgeUp,
        run: (c) => c.app.run.getState().stopParticleEffects(),
      },
    ],
    thumbnail: ParticleThumbnail,
  },
  terrain: {
    title: 'Terrain',
    one: 'terrain',
    group: 'world',
    icon: 'mountain',
    tone: 'green',
    message:
      'Worlds are made with it by this id, e.g. nf.worlds.create("realm", { terrain = "ruby_hills" }).',
    view: lazy(() =>
      import('@/editors/terrain/TerrainEditor').then((m) => ({ default: m.TerrainEditor })),
    ),
    viewState: TERRAIN_VIEW,
  },
  biome: {
    title: 'Biomes',
    one: 'biome',
    group: 'world',
    icon: 'leaf',
    tone: 'green',
    message:
      'Terrain areas and spawning rules name it by this id, e.g. "biome": "ruby_grove". Saving one restarts the dev server.',
    view: lazy(() =>
      import('@/editors/biome/BiomeEditor').then((m) => ({ default: m.BiomeEditor })),
    ),
    thumbnail: BiomeThumbnail,
  },
  sky: {
    title: 'Skies',
    one: 'sky',
    group: 'world',
    icon: 'cloud',
    tone: 'orange',
    message:
      'Scripts set it by this id, e.g. world:set_sky("blood_moon"). Saving one restarts the dev server.',
    view: lazy(() => import('@/editors/sky/SkyEditor').then((m) => ({ default: m.SkyEditor }))),
  },
  buff: {
    title: 'Buffs',
    one: 'buff',
    group: 'world',
    icon: 'flask',
    tone: 'orange',
    message: 'Scripts give it by this id, e.g. player:add_buff("ironskin", 20 * 60).',
    view: lazy(() => import('@/editors/buff/BuffEditor').then((m) => ({ default: m.BuffEditor }))),
  },
  dimension_type: {
    title: 'Dimension types',
    one: 'dimension type',
    group: 'world',
    icon: 'layers',
    tone: 'cyan',
    message:
      'Worlds name it by this id in nf.worlds.create and netherforge.json\'s "worlds", e.g. "dimensionType": "deep". Saving one restarts the dev server.',
    view: lazy(() =>
      import('@/editors/dimension_type/DimensionTypeEditor').then((m) => ({
        default: m.DimensionTypeEditor,
      })),
    ),
  },
  structure: {
    title: 'Structures',
    one: 'structure',
    group: 'world',
    icon: 'structure',
    tone: 'green',
    message:
      'Scripts place it by this id, e.g. world:place_structure("house", position). Capture it from the dev server next.',
    view: lazy(() =>
      import('@/editors/structure/StructureEditor').then((m) => ({ default: m.StructureEditor })),
    ),
    viewState: STRUCTURE_VIEW,
  },
  build: {
    title: 'Builds',
    one: 'build',
    group: 'world',
    icon: 'blocks',
    tone: 'green',
    message:
      'Scripts place it a few thousand blocks a tick by this id, e.g. world:place_gradually("castle", position). Capture it from the dev server, or import a schematic, next.',
    view: lazy(() =>
      import('@/editors/builds/BuildEditor').then((m) => ({ default: m.BuildEditor })),
    ),
  },
  map: {
    title: 'Maps',
    one: 'map',
    group: 'world',
    icon: 'map',
    tone: 'green',
    message:
      'Scripts copy it by this id, e.g. nf.worlds.copy("arena", "arena_1"). Save a dev-server world into it next.',
    view: lazy(() =>
      import('@/editors/map/MapEditor').then((m) => ({
        default: m.MapEditor,
      })),
    ),
    viewState: MAP_VIEW,
    thumbnail: folderPicture('map', 'icon.png'),
  },
  datapack: {
    title: 'Datapacks',
    one: 'datapack',
    group: 'world',
    icon: 'box',
    tone: 'green',
    message:
      "The game's own worldgen JSON, passed through: data/<namespace>/worldgen/... beside its pack.mcmeta. Saving one restarts the dev server.",
    view: lazy(() =>
      import('@/editors/datapack/DatapackEditor').then((m) => ({ default: m.DatapackEditor })),
    ),
    thumbnail: folderPicture('datapack', 'pack.png'),
  },
  module: {
    title: 'Modules',
    one: 'module',
    group: 'code',
    icon: 'module',
    tone: 'amber',
    message: 'Scripts load it by this id, e.g. require("greeter").',
    movesToPackage: true,
  },
  migration: {
    title: 'Migrations',
    one: 'migration',
    group: 'code',
    icon: 'database',
    tone: 'amber',
    message:
      "Name it NNN_name, like 001_init: they run in order on the package's own database, nf.db().",
  },
  wasm: {
    title: 'WebAssembly',
    one: 'WebAssembly module',
    group: 'code',
    icon: 'atom',
    tone: 'amber',
    message:
      'Scripts run it by this id, e.g. nf.wasm.instantiate("noise"). Copy its module.wasm (built for wasm32-wasip1) into the folder next.',
    view: lazy(() => import('@/editors/wasm/WasmEditor').then((m) => ({ default: m.WasmEditor }))),
  },
} satisfies KindEntries

/** The explorer's sections, in order: every kind is in one (its entry's `group`). */
export const KIND_GROUPS: readonly { id: KindGroup; title: string }[] = [
  { id: 'gameplay', title: 'Gameplay' },
  { id: 'interface', title: 'Interface' },
  { id: 'art', title: 'Art & sound' },
  { id: 'world', title: 'World' },
  { id: 'code', title: 'Code' },
]

/** Every kind, in the explorer's order: group by group, each in its entries' order. */
export const KIND_ORDER: KindId[] = KIND_GROUPS.flatMap((group) =>
  (Object.keys(KIND_CONTRIBUTIONS) as KindId[]).filter(
    (kind) => KIND_CONTRIBUTIONS[kind].group === group.id,
  ),
)

export const kindContribution = (kind: KindId): KindContribution => KIND_CONTRIBUTIONS[kind]

type Entries = typeof KIND_CONTRIBUTIONS

/** A kind that keeps a per-document view (see `editors/views.ts`). */
export type ViewKind = {
  [K in KindId]: Entries[K] extends { viewState: ViewStateSpec<object, unknown> } ? K : never
}[KindId]

/** A kind's per-document view state, as its entry types it. */
export type ViewStateOf<K extends ViewKind> = Entries[K] extends {
  viewState: ViewStateSpec<infer V, unknown>
}
  ? V
  : never

/** What a kind's selection holds, as its entry types it. */
export type SelectionOf<K extends ViewKind> = Entries[K] extends {
  viewState: ViewStateSpec<object, infer S>
}
  ? S
  : never

/** A kind whose resources open in a tab of their own: its id is the tab's type. */
export type KindTabType = EditedKind | BinaryKindId

/**
 * A resource's own tab: its id, or for Minecraft's own files what's on disk
 * (`house.nbt`, `arena/`), which is what a binary kind's tab shows.
 */
function resourceLabel(path: string): string {
  const resource = resourceOf(path)
  if (!resource) return basename(path)
  if (KINDS[resource.kind].contents === 'json') return resource.id
  return isFileKind(resource.kind) ? basename(path) : `${basename(path)}/`
}

const kindTab = (kind: KindTabType): TabView => {
  const contribution = KIND_CONTRIBUTIONS[kind]
  return {
    icon: contribution.icon,
    label: resourceLabel,
    view: contribution.view,
    document: KINDS[kind].contents === 'json',
    resource: true,
  }
}

/** The tabs a file opens in when it isn't a resource's own: Lua, raw JSON, the project's manifest. */
export const FILE_TABS = {
  script: {
    icon: 'code',
    label: basename,
    // Monaco is a large part of the bundle: loaded with the first tab that needs it.
    view: lazy(() => import('@/editors/script/CodeEditor').then((m) => ({ default: m.LuaEditor }))),
    document: true,
    resource: false,
  },
  sql: {
    icon: 'database',
    label: basename,
    view: lazy(() => import('@/editors/script/CodeEditor').then((m) => ({ default: m.SqlEditor }))),
    document: true,
    resource: false,
  },
  json: {
    icon: 'file',
    label: basename,
    view: lazy(() =>
      import('@/editors/script/CodeEditor').then((m) => ({ default: m.JsonEditor })),
    ),
    document: true,
    resource: false,
  },
  project: {
    icon: 'gear',
    label: basename,
    view: lazy(() =>
      import('@/editors/project/ProjectEditor').then((m) => ({ default: m.ProjectEditor })),
    ),
    document: true,
    resource: false,
  },
} satisfies Record<string, TabView>

/** Every tab a resource or a file opens in. */
export const EDITOR_TABS = {
  ...(Object.fromEntries(
    (Object.keys(KIND_CONTRIBUTIONS) as KindId[])
      .filter(
        (kind): kind is KindTabType =>
          KINDS[kind].contents !== 'lua' && KINDS[kind].contents !== 'sql',
      )
      .map((kind) => [kind, kindTab(kind)]),
  ) as Record<KindTabType, TabView>),
  ...FILE_TABS,
}
