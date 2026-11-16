import type { PlatformFile } from '../types.ts'

export default {
  source: 'Mannequins.kt',
  decls: [
    {
      kind: 'interface',
      name: 'MannequinOps',
      doc: "Mannequins (`minecraft:mannequin`): figures that look like players. What\nthey share with any living entity (health, equipment, a custom name,\nturning) is [WorldEntityOps]'; this is what only they have, read whole\nand written back whole.",
      properties: [],
      methods: [
        {
          name: 'look',
          doc: "How the mannequin [id] looks now; null when it's gone, unloaded or not a mannequin.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
          ],
          returns: 'MannequinLook?',
        },
        {
          name: 'setLook',
          doc: "Makes the mannequin [id] look as [look] says, changing only what differs; false when it's gone.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'look',
              type: 'MannequinLook',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
    {
      kind: 'record',
      name: 'MannequinLook',
      doc: "Everything about how a mannequin looks and stands that a living entity doesn't have.",
      fields: [
        {
          name: 'skin',
          type: 'MannequinSkin',
          default: 'MannequinSkin.Default',
        },
        {
          name: 'description',
          type: 'String?',
          default: 'null',
          doc: 'The line under its name, as MiniMessage; null for none. A new mannequin shows the game\'s "NPC".',
        },
        {
          name: 'immovable',
          type: 'Boolean',
          default: 'false',
          doc: 'Pushed by nothing: players, mobs, water, explosions.',
        },
        {
          name: 'leftHanded',
          type: 'Boolean',
          default: 'false',
        },
        {
          name: 'pose',
          type: 'MannequinPose',
          default: 'MannequinPose.STANDING',
        },
        {
          name: 'skinParts',
          type: 'Set<SkinPart>',
          default: 'SkinPart.entries.toSet()',
          doc: "The parts of its skin's outer layer it shows.",
        },
      ],
    },
    {
      kind: 'union',
      name: 'MannequinSkin',
      cases: [
        {
          name: 'Default',
          doc: "The game's own default skin.",
          fields: [],
        },
        {
          name: 'Player',
          fields: [
            {
              name: 'name',
              type: 'String',
            },
          ],
        },
        {
          name: 'Texture',
          fields: [
            {
              name: 'texture',
              type: 'SkinTexture',
            },
          ],
        },
      ],
    },
    {
      kind: 'enum',
      name: 'MannequinPose',
      doc: 'The poses a mannequin may stand in, as scripts name them.',
      params: [
        {
          name: 'luaName',
          type: 'String',
        },
      ],
      entries: [
        {
          name: 'STANDING',
          args: ['"standing"'],
        },
        {
          name: 'CROUCHING',
          args: ['"crouching"'],
        },
        {
          name: 'SWIMMING',
          args: ['"swimming"'],
        },
        {
          name: 'FALL_FLYING',
          args: ['"fall_flying"'],
        },
        {
          name: 'SLEEPING',
          args: ['"sleeping"'],
        },
      ],
    },
    {
      kind: 'enum',
      name: 'SkinPart',
      doc: "A part of a skin's outer layer, as scripts name it.",
      params: [
        {
          name: 'luaName',
          type: 'String',
        },
      ],
      entries: [
        {
          name: 'CAPE',
          args: ['"cape"'],
        },
        {
          name: 'JACKET',
          args: ['"jacket"'],
        },
        {
          name: 'LEFT_SLEEVE',
          args: ['"left_sleeve"'],
        },
        {
          name: 'RIGHT_SLEEVE',
          args: ['"right_sleeve"'],
        },
        {
          name: 'LEFT_PANTS',
          args: ['"left_pants"'],
        },
        {
          name: 'RIGHT_PANTS',
          args: ['"right_pants"'],
        },
        {
          name: 'HAT',
          args: ['"hat"'],
        },
      ],
    },
  ],
} satisfies PlatformFile
