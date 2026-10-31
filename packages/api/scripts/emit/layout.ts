/**
 * How the API reference (`docs/reference/`) is laid out: which section of the site each page is
 * in, and how a class page with many functions or events splits them into sections. Only the
 * docs read this. `pnpm generate` fails when something new isn't placed (`checkLayout`), naming
 * what to add here.
 */
import { articles } from '../../src/spec/articles.ts'
import type { ApiSpec, LuaClass } from '../../src/types.ts'

/** A section of the reference: its pages, by class or value name, or an article's or page's file. */
export interface Category {
  title: string
  /** What the index says under its heading. */
  doc: string
  pages: string[]
}

/** The sections of the reference, in the order the index and the sidebar show them. */
export const CATEGORIES: Category[] = [
  {
    title: 'Basics',
    doc: 'How scripts run, how events work, and the rules every name in the API follows.',
    pages: ['scripts.md', 'events.md', 'types.md', 'naming.md', 'spaces.md', 'packages.md'],
  },
  {
    title: 'Core',
    doc: 'Events, timers, tasks, saved data and the server itself.',
    pages: [
      'nf',
      'Task',
      'Subscription',
      'nf.server',
      'nf.commands',
      'Sender',
      'nf.schedule',
      'Schedule',
    ],
  },
  {
    title: 'Centities and effects',
    doc: 'Your own entities, the particle effects and cutscenes made in the editor.',
    pages: [
      'nf.centities',
      'Centity',
      'Node',
      'nf.spawning',
      'nf.particles',
      'Effect',
      'nf.cutscenes',
      'Cutscene',
      'nf.images',
      'Image',
    ],
  },
  {
    title: 'Screens',
    doc: 'Walls of maps scripts draw on and play videos on.',
    pages: ['nf.screens', 'Screen', 'Playback'],
  },
  {
    title: 'Players and entities',
    doc: "Players, mobs and every other entity, and what's on a player's screen.",
    pages: [
      'nf.players',
      'Player',
      'nf.bots',
      'Bot',
      'Entity',
      'Living',
      'Mob',
      'Mannequin',
      'DroppedItem',
      'Hud',
      'Sidebar',
      'nf.bossbars',
      'BossBar',
      'nf.teams',
      'Team',
    ],
  },
  {
    title: 'Worlds and blocks',
    doc: "Worlds, blocks, the project's own blocks and structures.",
    pages: [
      'nf.worlds',
      'World',
      'WorldBorder',
      'Block',
      'nf.blocks',
      'ProjectBlock',
      'CustomBlock',
      'nf.structures',
    ],
  },
  {
    title: 'Items and inventories',
    doc: "Items, the project's own items, inventories, recipes and loot.",
    pages: ['nf.items', 'ProjectItem', 'Inventory', 'nf.recipes', 'nf.loot'],
  },
  {
    title: 'Menus and dialogs',
    doc: 'Chest menus and dialog screens.',
    pages: ['nf.menus', 'Menu', 'Slot', 'MenuTemplate', 'nf.dialogs', 'Dialog', 'Button'],
  },
  {
    title: 'Values and utilities',
    doc: 'Vectors and locations, text, looks, time, randomness, maths, JSON, encodings and hashes.',
    pages: [
      'Vec3',
      'Location',
      'nf.text',
      'nf.looks',
      'nf.packs',
      'nf.time',
      'nf.random',
      'Random',
      'nf.math',
      'nf.json',
      'nf.encoding',
      'nf.crypto',
    ],
  },
  {
    title: 'Files, data, the web and computation',
    doc: 'Files, databases, web requests both ways, other plugins and WebAssembly modules.',
    pages: [
      'nf.files',
      'File',
      'Database',
      'nf.http',
      'HttpStream',
      'WebSocket',
      'nf.web',
      'Route',
      'WebResponse',
      'OAuthFlow',
      'nf.economy',
      'nf.placeholders',
      'nf.wasm',
      'WasmInstance',
      'WasmProcess',
    ],
  },
  {
    title: 'Networks',
    doc: 'The other servers behind the same proxy: messages, requests, shared maps, who is where, sending players.',
    pages: ['nf.network', 'NetworkChannel', 'NetworkMap'],
  },
  {
    title: 'Testing',
    doc: 'What `*_test.lua` files run on.',
    pages: ['nf.test'],
  },
  {
    title: 'Terrain scripts',
    doc: 'The separate API a terrain generator script gets.',
    pages: ['terrain-scripts.md'],
  },
]

/** A section of a class page: functions (or events) that belong together. */
export interface Group {
  title: string
  /** Names, in the order the page shows them. */
  names: string[]
}

const group = (title: string, names: string): Group => ({ title, names: names.split(/\s+/) })

/**
 * The function sections of the classes with many functions. Every function but `on`, `once` and
 * `emit` (which go with the class's events) is in exactly one.
 */
export const FUNCTION_GROUPS: Record<string, Group[]> = {
  'nf.test': [
    group('Tests and time', 'case advance'),
    group('Players and their input', 'player raise click release input shown'),
    group('Worldgen', 'terrain'),
    group('Web connections', 'connections accept receive sent disconnect request oauth_token'),
    group(
      'Server networks',
      'network_server network_publish network_received network_answer network_request network_write network_join network_quit network_send',
    ),
    group('WebAssembly', 'stub_wasm'),
  ],
  nf: [
    group('Timers and tasks', 'after every task wait wait_until wait_for instructions_left'),
    group('Data and settings', 'data db config'),
  ],
  'nf.server': [
    group(
      'Clock and performance',
      'tick unix_time ticks_per_second tick_milliseconds minecraft_version',
    ),
    group('The console', 'broadcast run'),
    group(
      'Settings',
      'max_players set_max_players motd set_motd is_whitelist_enabled set_whitelist_enabled',
    ),
  ],
  Screen: [
    group('Identity', 'name exists width height remove'),
    group(
      'Drawing',
      'clear pixel set_pixel pixels set_pixels fill rectangle line circle ellipse polygon text image copy',
    ),
    group('Hanging and items', 'place take_down location facing item'),
    group('Who sees it', 'show_to hide_from is_shown_to viewers for_player player release'),
    group('Videos', 'play playback'),
  ],
  Playback: [
    group('What it is', 'video screen length exists'),
    group(
      'Controls',
      'is_paused pause resume stop time seek speed set_speed volume set_volume is_looping set_looping',
    ),
  ],
  Centity: [
    group(
      'Identity and lifetime',
      'id kind exists is_natural is_temporary lifetime set_lifetime keep remove data',
    ),
    group('Nodes', 'node nodes roots add_node properties bounds'),
    group(
      'Position and facing',
      'location position world yaw set_yaw look_at to_local to_world teleport follow stop_following',
    ),
    group('Health and damage', 'health max_health set_health set_max_health damage heal is_dead'),
    group(
      'Stats and buffs',
      'stat stats modifiers set_modifiers add_buff remove_buff has_buff buff_ticks buffs',
    ),
    group(
      'Body and movement',
      'has_body body set_body velocity set_velocity add_velocity walk_velocity set_walk_velocity jump is_on_ground is_in_liquid wall launch is_launched stop_flight',
    ),
    group('Pathfinding', 'move_to stop_pathing has_path path_target'),
    group(
      'Animation',
      'play_animation stop_animation pause_animation resume_animation is_animation_playing playing_animations animations animation_tick seek_animation animation_speed set_animation_speed',
    ),
    group(
      'Glow and visibility',
      'is_glowing set_glowing glow_color set_glow_color hide_from show_to is_hidden_from view_range set_view_range',
    ),
  ],
  Node: [
    group('The node tree', 'name centity parent children add_child remove'),
    group(
      'Transform',
      'translation rotation scale set_translation set_rotation set_scale tween is_tweening stop_tween interpolation_ticks set_interpolation_ticks',
    ),
    group(
      'World space',
      'world_position world_rotation world_direction to_local to_world look_at rotate',
    ),
    group('Ropes', 'stretch_between is_rope rope_between release_rope'),
    group(
      'What it shows',
      'display_kind display_block set_display_block display_item set_display_item model set_model display_text set_display_text display_text_style set_display_text_style brightness set_brightness billboard set_billboard',
    ),
    group(
      'Rects and images',
      'image set_image display_color set_display_color display_size set_display_size display_anchor set_display_anchor',
    ),
    group(
      'Visibility, clicks and glow',
      'is_visible set_visible is_clickable set_clickable is_glowing set_glowing glow_color set_glow_color',
    ),
    group(
      'Physics',
      'is_physics_enabled set_physics_enabled velocity set_velocity add_velocity angular_velocity set_angular_velocity mass apply_impulse apply_impulse_at apply_force has_gravity set_gravity is_kinematic set_kinematic is_on_ground is_asleep wake',
    ),
  ],
  Entity: [
    group('Identity', 'id kind exists is_player is_living is_mob remove data'),
    group(
      'Position and movement',
      'location position world eye_position yaw pitch direction set_yaw set_pitch look_at teleport velocity set_velocity add_velocity is_on_ground',
    ),
    group(
      'Names, tags and teams',
      'name custom_name set_custom_name is_custom_name_visible set_custom_name_visible tags has_tag add_tag remove_tag team',
    ),
    group(
      'Appearance and sound',
      'is_glowing set_glowing is_visible set_visible hide_from show_to is_hidden_from is_silent set_silent',
    ),
    group('Gravity and damage', 'has_gravity set_gravity is_invulnerable set_invulnerable'),
    group('Riding and inventory', 'passengers add_passenger remove_passenger vehicle inventory'),
    group('What it looks at', 'target_block target_entity target_centity'),
  ],
  Living: [
    group('Health', 'health set_health max_health damage heal'),
    group(
      'Attributes',
      'attribute attribute_base set_attribute_base attribute_modifiers add_attribute_modifier remove_attribute_modifier',
    ),
    group('Potion effects', 'effects has_effect add_effect remove_effect'),
    group('Equipment', 'equipment set_equipment'),
  ],
  Bot: [
    group('Leaving', 'leave'),
    group('Moving', 'walk_to stop look_at jump set_sneaking set_sprinting'),
    group('Talking', 'chat'),
    group('Clicking', 'attack interact use_item release_item swing use_block break_block'),
    group('Items and menus', 'swap_hands drop_item click_slot'),
    group('Dying', 'respawn'),
  ],
  Mob: [
    group('Target and AI', 'target set_target has_ai set_ai'),
    group('Pathfinding', 'move_to stop_pathing has_path path_target'),
    group('Goals', 'goals add_goal remove_goal clear_goals'),
  ],
  Player: [
    group(
      'Who they are',
      'name display_name set_display_name locale ping first_played last_seen session data',
    ),
    group(
      'Messages, sounds and books',
      'send_message send_actionbar send_title clear_title play_sound stop_sound spawn_particle open_book',
    ),
    group(
      'Commands and permissions',
      'run has_permission set_permission unset_permission permissions is_operator',
    ),
    group('Moderation', 'kick is_banned ban unban is_whitelisted set_whitelisted'),
    group(
      'Game mode, food and experience',
      'game_mode set_game_mode food set_food saturation set_saturation level set_level experience_progress set_experience_progress give_experience',
    ),
    group(
      'Movement',
      'is_flying set_flying can_fly set_can_fly walk_speed set_walk_speed fly_speed set_fly_speed is_sneaking is_sprinting',
    ),
    group(
      'Stats and buffs',
      'stat stats modifiers set_modifiers add_buff remove_buff has_buff buff_ticks buffs',
    ),
    group(
      'Items',
      'held_item set_held_item off_hand_item set_off_hand_item cursor_item set_cursor_item held_slot set_held_slot give_item item_cooldown set_item_cooldown ender_chest storage refresh_items',
    ),
    group(
      'Recipes and advancements',
      'discover_recipe undiscover_recipe has_discovered_recipe grant_advancement revoke_advancement has_advancement advancement_progress',
    ),
    group(
      'Menus, inventories and dialogs',
      'open_menu menu close_menu open_inventory close_inventory open_dialog close_dialog',
    ),
    group('On their screen', 'hud sidebar bossbars below_name set_below_name'),
    group(
      'The player list',
      'tab_header set_tab_header tab_footer set_tab_footer tab_name set_tab_name tab_order set_tab_order is_listed_for set_listed_for',
    ),
    group(
      'What they see of the world',
      'sky set_sky clear_sky border has_own_border reset_border send_block_change reset_block send_equipment_change camera set_camera compass_target set_compass_target view_distance set_view_distance resource_pack_status push_resource_pack pop_resource_pack',
    ),
  ],
  World: [
    group(
      'The world itself',
      'name exists environment is_managed unload location spawn_location set_spawn_location min_height max_height',
    ),
    group(
      'Time, weather and sky',
      'time_of_day set_time_of_day is_day is_night day_count moon_phase weather set_weather sky set_sky clear_sky',
    ),
    group('Blocks', 'block blocks set_block fill_blocks fill_gradually flood highest_block'),
    group('Chunks and biomes', 'is_chunk_loaded load_chunk locate_biome'),
    group('Entities', 'players centities entities spawn_entity spawn_item'),
    group('Particles, sounds and explosions', 'spawn_particle play_sound explode strike_lightning'),
    group('Raycasts and overlaps', 'raycast sweep overlap_box overlap_sphere overlap_cone nearest'),
    group(
      'Game rules and spawning',
      'game_rule set_game_rule spawn_limit set_spawn_limit spawn_interval set_spawn_interval',
    ),
    group('Border and structures', 'border save_structure place_structure place_gradually'),
  ],
  Block: [
    group('Where it is', 'world position location relative'),
    group(
      "What's there",
      'kind state property properties is_air is_solid is_liquid custom biome light_level sky_light_level',
    ),
    group('Changing it', 'set_state set_property break_naturally'),
    group('Contents and data', 'inventory data'),
  ],
  Inventory: [
    group('The inventory', 'exists kind size holder viewers'),
    group('Slots', 'item set_item items clear'),
    group('Adding and finding items', 'add_item remove_item count_item has_item first_slot'),
  ],
  BossBar: [
    group('Who sees it', 'show_to hide_from viewers'),
    group(
      'How it looks',
      'text set_text icon set_icon progress set_progress color set_color style set_style',
    ),
    group('Following an entity', 'attach detach attached'),
    group('Lifetime', 'exists remove'),
  ],
  Team: [
    group('Members', 'name exists add_member remove_member has_member members remove'),
    group(
      'Names and colours',
      'display_name set_display_name prefix set_prefix suffix set_suffix color set_color',
    ),
    group(
      'Rules',
      'has_friendly_fire set_friendly_fire can_see_invisible_teammates set_can_see_invisible_teammates nametags set_nametags collision set_collision',
    ),
  ],
  Menu: [
    group('The window', 'id kind template exists is_shared context size rows'),
    group('Title and look', 'title set_title skin set_skin is_locked set_locked'),
    group('Items', 'slot item set_item add_item items set_items fill clear shift_into refresh'),
    group('Viewers', 'viewers open_for close_for close_all cursor_item set_cursor_item'),
  ],
  Slot: [
    group('The slot', 'index menu item set_item'),
    group('Binding to an inventory', 'bind bind_equipment unbind binding'),
    group('What it accepts', 'set_rule rule accepts'),
  ],
  File: [
    group('Paths', 'path name parent child exists is_folder'),
    group('Reading and writing', 'read write append lines read_json write_json'),
    group('Files and folders', 'size modified_time children rename copy_to create_folder delete'),
  ],
}

/** The event sections of the classes with many events. */
export const EVENT_GROUPS: Record<string, Group[]> = {
  nf: [
    group('Server and scripts', 'tick unload setting_changed server_list_ping'),
    group(
      'Players joining and leaving',
      'player_join player_quit player_kick player_arrive player_resource_pack_status',
    ),
    group('Players: chat and commands', 'player_chat player_command'),
    group(
      'Players: moving',
      'player_move player_teleport player_change_world player_input player_sneak player_sprint player_fly player_jump player_swing',
    ),
    group(
      'Players: clicking and items',
      'player_interact player_interact_entity player_use_item player_stop_using_item player_consume_item player_break_item player_drop_item player_pickup_item player_swap_hands player_change_slot item_view',
    ),
    group(
      'Players: life and progress',
      'player_death player_respawn player_change_game_mode player_change_food player_gain_experience player_change_level player_complete_advancement',
    ),
    group(
      'Players: crafting and containers',
      'player_open_inventory player_close_inventory player_click_inventory player_drag_inventory player_craft player_prepare_craft player_prepare_anvil player_prepare_smithing player_prepare_grindstone player_enchant_item recipe_craft furnace_smelt',
    ),
    group(
      'Entities',
      'entity_spawn entity_damage entity_heal entity_death entity_combust entity_change_effect entity_target entity_explode entity_shoot_bow projectile_launch projectile_hit entity_mount entity_dismount',
    ),
    group('Vehicles', 'vehicle_move vehicle_damage vehicle_destroy'),
    group(
      'Blocks',
      'block_break block_start_break block_drop_item block_place block_explode block_ignite block_burn block_spread block_flow block_grow block_fade block_form leaves_decay piston_extend piston_retract block_redstone sign_change',
    ),
    group(
      'Worlds, weather and chunks',
      'world_dusk world_dawn weather_change thunder_change lightning_strike world_load world_unload chunk_load chunk_generated chunk_unload',
    ),
    group(
      'Centities, menus and dialogs',
      'centity_click centity_natural_spawn menu_open menu_close menu_click dialog_press',
    ),
    group(
      'The network',
      'network_connect network_disconnect network_server_connect network_server_disconnect network_player_join network_player_switch network_player_quit',
    ),
  ],
  Player: [
    group('Joining and leaving', 'join quit kick arrive resource_pack_status'),
    group('Chat and commands', 'chat command'),
    group('Moving', 'move teleport change_world input sneak sprint fly jump swing'),
    group(
      'Clicking and items',
      'interact interact_entity use_item stop_using_item consume_item break_item drop_item pickup_item swap_hands change_slot',
    ),
    group(
      'Life and progress',
      'death respawn change_game_mode change_food gain_experience change_level complete_advancement buff_add buff_end',
    ),
    group(
      'Crafting and containers',
      'open_inventory close_inventory click_inventory drag_inventory craft prepare_craft prepare_anvil prepare_smithing prepare_grindstone enchant_item',
    ),
  ],
  World: [
    group(
      'Blocks',
      'block_break block_start_break block_drop_item block_place block_explode block_ignite block_burn block_spread block_flow block_grow block_fade block_form leaves_decay piston_extend piston_retract block_redstone sign_change furnace_smelt',
    ),
    group('Entities', 'entity_spawn'),
    group('Day, night and weather', 'dusk dawn weather_change thunder_change lightning_strike'),
    group('Chunks', 'chunk_load chunk_unload chunk_generated'),
  ],
  Centity: [
    group('Lifetime', 'spawn remove tick chunk_load chunk_unload'),
    group('Clicks and damage', 'click hit damage death'),
    group('Animation', 'animation_start animation_end'),
    group('Movement', 'land bump touch bounce stick flight_end path_end'),
    group('Buffs', 'buff_add buff_end'),
  ],
}

/** Classes with at least this many functions (besides `on`/`once`/`emit`) must be grouped. */
const GROUP_FUNCTIONS_FROM = 15
/** Classes with at least this many events must be grouped. */
const GROUP_EVENTS_FROM = 15

export const EVENT_FUNCTIONS = new Set(['on', 'once', 'emit'])

/** What each group of [groups] must hold exactly: every one of [names], once. */
function checkGroups(where: string, groups: Group[], names: string[]): string[] {
  const problems: string[] = []
  const placed = groups.flatMap((it) => it.names)
  for (const name of names)
    if (!placed.includes(name)) problems.push(`${where}: "${name}" isn't in any group`)
  for (const name of placed) {
    if (!names.includes(name))
      problems.push(`${where}: group names "${name}", which it doesn't have`)
    if (placed.indexOf(name) !== placed.lastIndexOf(name))
      problems.push(`${where}: "${name}" is in more than one group`)
  }
  return problems
}

/** Throws, listing everything, unless every page, function and event is placed exactly once. */
export function checkLayout(spec: ApiSpec, extraPages: string[]): void {
  const problems: string[] = []
  const pages = [
    ...spec.classes.map((it) => it.name),
    ...(spec.values ?? []).map((it) => it.name),
    ...articles.map((it) => it.file),
    ...extraPages,
  ]
  const placed = CATEGORIES.flatMap((it) => it.pages)
  for (const page of pages)
    if (!placed.includes(page)) problems.push(`"${page}" isn't in any of CATEGORIES`)
  for (const page of placed) {
    if (!pages.includes(page)) problems.push(`CATEGORIES names "${page}", which isn't a page`)
    if (placed.indexOf(page) !== placed.lastIndexOf(page))
      problems.push(`"${page}" is in more than one of CATEGORIES`)
  }
  const classes = new Map<string, LuaClass>(spec.classes.map((it) => [it.name, it]))
  for (const name of [...Object.keys(FUNCTION_GROUPS), ...Object.keys(EVENT_GROUPS)])
    if (!classes.has(name)) problems.push(`groups for "${name}", which isn't a class`)
  for (const cls of spec.classes) {
    const functions = cls.functions.map((it) => it.name).filter((it) => !EVENT_FUNCTIONS.has(it))
    const events = (cls.events ?? []).map((it) => it.name)
    const fnGroups = FUNCTION_GROUPS[cls.name]
    if (fnGroups) problems.push(...checkGroups(`FUNCTION_GROUPS.${cls.name}`, fnGroups, functions))
    else if (functions.length >= GROUP_FUNCTIONS_FROM)
      problems.push(`${cls.name} has ${functions.length} functions: give it FUNCTION_GROUPS`)
    const eventGroups = EVENT_GROUPS[cls.name]
    if (eventGroups) problems.push(...checkGroups(`EVENT_GROUPS.${cls.name}`, eventGroups, events))
    else if (events.length >= GROUP_EVENTS_FROM)
      problems.push(`${cls.name} has ${events.length} events: give it EVENT_GROUPS`)
  }
  if (problems.length)
    throw new Error(
      `The reference's layout (packages/api/scripts/emit/layout.ts) is out of date:\n  ${problems.join('\n  ')}`,
    )
}
