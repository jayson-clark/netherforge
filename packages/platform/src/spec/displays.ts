import type { PlatformFile } from '../types.ts'

export default {
  source: 'Displays.kt',
  decls: [
    {
      kind: 'record',
      name: 'EntityTag',
      doc: "Marks an entity as part of a centity, written into the entity's persistent\ndata so it survives restarts and can be traced back to its node.",
      fields: [
        {
          name: 'instance',
          type: 'UUID',
        },
        {
          name: 'node',
          type: 'String',
        },
        {
          name: 'role',
          type: 'EntityRole',
        },
      ],
    },
    {
      kind: 'enum',
      name: 'EntityRole',
      doc: "What an entity is to its instance: a node's display or hitbox, or the\ninstance's **anchor**, the invisible vehicle at the anchor its displays\n(and the hitboxes that sit at the anchor) ride, so moving the centity is\none teleport. An anchor's tag has an empty node.\n\nA [SCREEN] entity is no centity's: it's an item frame a screen hangs in,\nits tag's instance the screen and its node the map (`column,row`).",
      params: [],
      entries: [
        {
          name: 'DISPLAY',
          args: [],
        },
        {
          name: 'HITBOX',
          args: [],
        },
        {
          name: 'ANCHOR',
          args: [],
        },
        {
          name: 'SCREEN',
          args: [],
        },
      ],
    },
    {
      kind: 'record',
      name: 'StructureMarker',
      doc: "A `marker` entity in a structure that asks for a centity ([centity], as a\nscript would name it) where it stands: [at] is its position and facing.\nThe marker is an ordinary entity the structure's template holds (so it\nis placed, rotated and mirrored with the structure); the adapter finds\nthe ones tagged `nf.centity.<centity>` ([TAG_PREFIX]). See `StructureSpawns`.",
      fields: [
        {
          name: 'id',
          type: 'UUID',
        },
        {
          name: 'centity',
          type: 'String',
        },
        {
          name: 'at',
          type: 'Location',
        },
      ],
    },
    {
      kind: 'record',
      name: 'DisplayPose',
      doc: "How a display entity is placed relative to the anchor it stands on.\n\nEvery display of a centity rides the instance's anchor entity and carries\nits offset in [matrix], so moving the whole centity is one teleport.\n[cullSize] widens the client's culling box to the whole assembly; without\nit a display offset far from its entity vanishes at some camera angles.",
      fields: [
        {
          name: 'matrix',
          type: 'Matrix4',
        },
        {
          name: 'interpolationTicks',
          type: 'Int',
        },
        {
          name: 'cullSize',
          type: 'Double',
        },
      ],
    },
    {
      kind: 'record',
      name: 'DisplayLook',
      doc: 'How a display is drawn beyond what it shows ([DisplayDef]) and its pose:\nwhat scripts change at runtime, all at its defaults for a fresh node.',
      fields: [
        {
          name: 'glowing',
          type: 'Boolean',
          default: 'false',
        },
        {
          name: 'glowColor',
          type: 'Int?',
          default: 'null',
          doc: "`0xRRGGBB` for a block or item display's glow, or null for the default (white).",
        },
        {
          name: 'brightness',
          type: 'DisplayBrightness?',
          default: 'null',
          doc: 'Fixed light levels, or null to be lit by where it stands.',
        },
        {
          name: 'billboard',
          type: 'Billboard?',
          default: 'null',
          doc: "Null leaves it as its content says (a text display's `billboard`; fixed for the others).",
        },
        {
          name: 'viewRange',
          type: 'Double',
          default: '1.0',
          doc: "Minecraft's view range: a multiplier, 1 being 64 blocks at the client's 100% entity distance.",
        },
        {
          name: 'teleportTicks',
          type: 'Int',
          default: '0',
          doc: 'Ticks a teleport of the entity is eased over, 0–59.',
        },
        {
          name: 'textOpacity',
          type: 'Int?',
          default: 'null',
          doc: "A text display's text opacity, 0–255, or null for opaque.",
        },
        {
          name: 'item',
          type: 'ItemData?',
          default: 'null',
          doc: "An item display's whole item, or null for its content's bare item.",
        },
      ],
    },
    {
      kind: 'record',
      name: 'DisplayBrightness',
      doc: 'Block and sky light, each 0–15.',
      fields: [
        {
          name: 'block',
          type: 'Int',
        },
        {
          name: 'sky',
          type: 'Int',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'EntityOps',
      doc: 'The entities centities are made of: one display per node that draws\nsomething, one interaction entity per node with a hitbox.',
      properties: [],
      methods: [
        {
          name: 'spawnDisplay',
          doc: "Spawns a display at [at] showing [display] posed by [pose], or null if it can't (unknown block or item).",
          params: [
            {
              name: 'at',
              type: 'Location',
            },
            {
              name: 'display',
              type: 'DisplayDef',
            },
            {
              name: 'pose',
              type: 'DisplayPose',
            },
            {
              name: 'tag',
              type: 'EntityTag',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'spawnHitbox',
          doc: 'Spawns an interaction entity: [width] square footprint, standing on [at].',
          params: [
            {
              name: 'at',
              type: 'Location',
            },
            {
              name: 'width',
              type: 'Double',
            },
            {
              name: 'height',
              type: 'Double',
            },
            {
              name: 'tag',
              type: 'EntityTag',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'spawnAnchor',
          doc: "Spawns an instance's anchor at [at]: an entity that draws nothing, which\nthe instance's other entities ride ([mount]). Riders stand exactly at\nits position, so moving it with [teleport] moves them all.",
          params: [
            {
              name: 'at',
              type: 'Location',
            },
            {
              name: 'tag',
              type: 'EntityTag',
            },
          ],
          returns: 'UUID?',
        },
        {
          name: 'mount',
          doc: "Makes [passenger] ride [vehicle]. True when it rides it now (already\ndid included); false when either can't be reached.",
          params: [
            {
              name: 'vehicle',
              type: 'UUID',
            },
            {
              name: 'passenger',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'dismount',
          doc: "Gets [passenger] off whatever it rides, where it is. Nothing when it rides nothing or can't be reached.",
          params: [
            {
              name: 'passenger',
              type: 'UUID',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'isLoaded',
          doc: "Whether the entity can be reached right now. False for one in an\nunloaded chunk and for one that's gone; [WorldOps.entitiesLoaded] tells\nthe two apart.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'updateDisplay',
          doc: 'Shows [display] on an existing display entity. False when the entity is\nthe wrong kind for it (a block display asked to show text), which only\na respawn fixes.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'display',
              type: 'DisplayDef',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'setPose',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'pose',
              type: 'DisplayPose',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'teleport',
          doc: "Moves an entity, keeping everything else about it: what rides it comes too. One that rides something doesn't move.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'to',
              type: 'Location',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'location',
          doc: "Where an entity is now, as the game has it: a rider sits where its vehicle\nputs it (above the feet of a player who carries it, lower when they sneak).\nNull when it can't be reached.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Location?',
        },
        {
          name: 'resizeHitbox',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'width',
              type: 'Double',
            },
            {
              name: 'height',
              type: 'Double',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'remove',
          doc: "Removes the entity; false when it can't be reached (see [isLoaded]).",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'setPersistent',
          doc: "Whether the server saves the entity with its chunk. A temporary\ncentity's entities aren't, so a crash can't leave them behind;\n`keep()` makes them so. Nothing for an entity that can't be reached.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'persistent',
              type: 'Boolean',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'setLook',
          doc: 'Draws a display as [look] says. Called after [updateDisplay] whenever\neither changed, so the look always has the last word over the content.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'look',
              type: 'DisplayLook',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'setHidden',
          doc: 'Hides an entity from one player, or shows it again. The server forgets\nthis when the player leaves and when the entity unloads, so the runtime\nkeeps its own record and says it again. Nothing for an offline player\nor an entity out of reach.',
          params: [
            {
              name: 'player',
              type: 'UUID',
            },
            {
              name: 'entity',
              type: 'UUID',
            },
            {
              name: 'hidden',
              type: 'Boolean',
            },
          ],
          returns: 'Unit',
        },
        {
          name: 'loadedTagged',
          doc: 'Every loaded entity carrying a tag, for cleaning up strays after a crash or a lost record.',
          params: [],
          returns: 'Map<UUID, EntityTag>',
        },
        {
          name: 'structureMarkers',
          doc: "Every loaded [StructureMarker], in the order the server lists its entities: what's still waiting for its centity.",
          params: [],
          returns: 'List<StructureMarker>',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'ClientEntityOps',
      doc: "Entities only some players' clients know of: the server keeps none of them in a world, so it neither ticks nor\ntracks them, and one costs nothing but what its viewers are sent. What a private centity is made of (a page of\none player's own, which as real entities hidden from everyone else would cost every other player's tracking:\neach tick, every entity is checked against every player near it).\n\nEverything is [EntityOps]'s, with these differences:\n - a new one is shown to nobody until [setViewers] names who sees it;\n - [setHidden] takes a player off its viewers ([hidden]) or puts them on;\n - it's never saved, so [setPersistent] does nothing, and [loadedTagged] and [structureMarkers] list none;\n - it doesn't unload with its chunk: [isLoaded] is true until it's removed;\n - only another of these can carry it ([mount] onto anything else is false);\n - a click on one of its hitboxes comes as a click on any of ours does, with its id.\n\nA viewer's client is sent it whenever it should know it, and only then: once the chunk it stands in has been\nsent to them (so after joining, coming near, respawning or changing worlds, all of which make the client\nforget it), and it's told to forget it when they stop being a viewer or it's removed.",
      extends: 'EntityOps',
      properties: [],
      methods: [
        {
          name: 'setViewers',
          doc: "Who sees [id] from now on: their clients are sent it (or told to forget it) as they need. Nothing for one that's gone.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'viewers',
              type: 'Set<UUID>',
            },
          ],
          returns: 'Unit',
        },
      ],
    },
  ],
} satisfies PlatformFile
