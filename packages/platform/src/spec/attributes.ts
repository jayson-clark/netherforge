import type { PlatformFile } from '../types.ts'

export default {
  source: 'Attributes.kt',
  decls: [
    {
      kind: 'record',
      name: 'AttributeModifierData',
      doc: "One modifier on an entity's attribute. [id] is namespaced: `shop:slow_zone` (the project's), `minecraft:sprinting`.\n[saved] false is one the server doesn't save with the entity (gone when it unloads or the server stops).",
      fields: [
        {
          name: 'id',
          type: 'String',
        },
        {
          name: 'amount',
          type: 'Double',
        },
        {
          name: 'operation',
          type: 'AttributeOperation',
        },
        {
          name: 'saved',
          type: 'Boolean',
          default: 'true',
        },
      ],
    },
    {
      kind: 'interface',
      name: 'AttributeOps',
      doc: "Entities' attributes (`entity:attribute`), by namespaced attribute id the\nruntime has checked the server has ([dev.netherforge.format.game.GameData.registry]).\nEverything answers null or false for an entity that isn't there now (as\n[WorldEntityOps] does) and for an attribute the entity doesn't have.",
      properties: [],
      methods: [
        {
          name: 'value',
          doc: "Its value now, every modifier applied, within the attribute's range.",
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'attribute',
              type: 'String',
            },
          ],
          returns: 'Double?',
        },
        {
          name: 'base',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'attribute',
              type: 'String',
            },
          ],
          returns: 'Double?',
        },
        {
          name: 'setBase',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'attribute',
              type: 'String',
            },
            {
              name: 'value',
              type: 'Double',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'modifiers',
          doc: 'Every modifier on it, whoever added it, in no particular order.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'attribute',
              type: 'String',
            },
          ],
          returns: 'List<AttributeModifierData>?',
        },
        {
          name: 'addModifier',
          doc: 'Adds a modifier, saved with the entity unless [AttributeModifierData.saved] says not, replacing one with the same id.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'attribute',
              type: 'String',
            },
            {
              name: 'modifier',
              type: 'AttributeModifierData',
            },
          ],
          returns: 'Boolean',
        },
        {
          name: 'removeModifier',
          doc: 'False when it had no modifier with [modifier] (namespaced) as its id.',
          params: [
            {
              name: 'id',
              type: 'UUID',
            },
            {
              name: 'attribute',
              type: 'String',
            },
            {
              name: 'modifier',
              type: 'String',
            },
          ],
          returns: 'Boolean',
        },
      ],
    },
  ],
} satisfies PlatformFile
