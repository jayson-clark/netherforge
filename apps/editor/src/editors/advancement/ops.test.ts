import { produce } from 'immer'
import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type AdvancementFile } from '@/core/format'
import {
  addCriterion,
  addGroup,
  conditionsText,
  criterionNames,
  customizeRequirements,
  duplicateCriterion,
  groupsOf,
  parseConditions,
  parseGroup,
  removeCriterion,
  removeGroup,
  renameCriterion,
  resetRequirements,
  setConditions,
  setDisplay,
  setDisplayEnabled,
  setGroup,
  setIconItem,
  setIconKind,
  setTrigger,
} from './ops'

const advancement: AdvancementFile = {
  display: { icon: { kind: 'minecraft:diamond' }, title: 'Shiny' },
  criteria: {
    found: { trigger: 'minecraft:inventory_changed', conditions: { items: [] } },
    given: {},
  },
}

const edit = (model: AdvancementFile, recipe: (draft: AdvancementFile) => void) =>
  produce(model, recipe)

describe('criteria', () => {
  it('adds one under a free name, into custom requirements too', () => {
    const next = edit(advancement, (draft) => {
      expect(addCriterion(draft)).toBe('criterion')
      expect(addCriterion(draft)).toBe('criterion_2')
    })
    expect(criterionNames(next)).toEqual(['found', 'given', 'criterion', 'criterion_2'])
    const custom = edit(next, customizeRequirements)
    expect(edit(custom, (draft) => void addCriterion(draft)).requirements?.at(-1)).toEqual([
      'criterion_3',
    ])
  })

  it('renames one in its order and in the requirements', () => {
    const custom = edit(advancement, (draft) => {
      draft.requirements = [['found', 'given']]
    })
    const next = edit(custom, (draft) => renameCriterion(draft, 'found', 'seen'))
    expect(criterionNames(next)).toEqual(['seen', 'given'])
    expect(next.requirements).toEqual([['seen', 'given']])
    expect(edit(custom, (draft) => renameCriterion(draft, 'found', 'given'))).toBe(custom)
  })

  it('removes one from the requirements, a group left empty with it', () => {
    const custom = edit(advancement, (draft) => {
      draft.requirements = [['found'], ['given']]
    })
    const next = edit(custom, (draft) => removeCriterion(draft, 'found'))
    expect(criterionNames(next)).toEqual(['given'])
    expect(next.requirements).toEqual([['given']])
  })

  it('duplicates one under a free name', () => {
    const next = edit(advancement, (draft) => void duplicateCriterion(draft, 'found'))
    expect(next.criteria?.found_copy).toEqual(advancement.criteria?.found)
    expect(next.criteria?.found_copy).not.toBe(next.criteria?.found)
    expect(edit(advancement, (draft) => void duplicateCriterion(draft, 'nope'))).toBe(advancement)
  })

  it('sets and clears a trigger; none takes the conditions with it', () => {
    const next = edit(advancement, (draft) => setTrigger(draft, 'given', 'minecraft:tick'))
    expect(next.criteria?.given).toEqual({ trigger: 'minecraft:tick' })
    const withConditions = edit(next, (draft) => setConditions(draft, 'given', { a: 1 }))
    expect(withConditions.criteria?.given?.conditions).toEqual({ a: 1 })
    expect(
      edit(withConditions, (draft) => setTrigger(draft, 'given', undefined)).criteria?.given,
    ).toEqual({})
  })
})

describe('requirements', () => {
  it('are every criterion on its own until customized', () => {
    expect(groupsOf(advancement)).toEqual([['found'], ['given']])
    const custom = edit(advancement, customizeRequirements)
    expect(custom.requirements).toEqual([['found'], ['given']])
    expect(edit(custom, resetRequirements).requirements).toBeUndefined()
  })

  it('edit groups as comma-separated names', () => {
    expect(parseGroup(' found , given,, ')).toEqual(['found', 'given'])
    const next = edit(advancement, (draft) => {
      customizeRequirements(draft)
      setGroup(draft, 0, parseGroup('found, given'))
      removeGroup(draft, 1)
      addGroup(draft)
    })
    expect(next.requirements).toEqual([['found', 'given'], []])
  })
})

describe('display', () => {
  it('is turned on with an icon and a title, and off again', () => {
    const bare = edit(advancement, (draft) => setDisplayEnabled(draft, false))
    expect(bare.display).toBeUndefined()
    expect(edit(bare, (draft) => setDisplayEnabled(draft, true)).display).toEqual({
      icon: { kind: 'minecraft:stone' },
      title: '',
    })
  })

  it('leaves a key out when cleared', () => {
    const next = edit(advancement, (draft) => {
      setDisplay(draft, 'frame', 'goal')
      setDisplay(draft, 'hidden', true)
    })
    expect(next.display).toMatchObject({ frame: 'goal', hidden: true })
    const cleared = edit(next, (draft) => setDisplay(draft, 'frame', undefined))
    expect('frame' in cleared.display!).toBe(false)
  })

  it('has a game item or a project item for an icon, never both', () => {
    const project = edit(advancement, (draft) => setIconItem(draft, 'ruby'))
    expect(project.display?.icon).toEqual({ item: 'ruby' })
    expect(edit(project, (draft) => setIconKind(draft, 'minecraft:emerald')).display?.icon).toEqual(
      {
        kind: 'minecraft:emerald',
      },
    )
  })
})

describe('conditions', () => {
  it('are JSON text, an object or nothing', () => {
    expect(conditionsText(undefined)).toBe('')
    expect(conditionsText({})).toBe('')
    expect(parseConditions('')).toBeUndefined()
    expect(parseConditions(conditionsText({ a: [1] }))).toEqual({ a: [1] })
    expect(parseConditions('[1]')).toBeNull()
    expect(parseConditions('{ nope')).toBeNull()
  })
})

describe('the file', () => {
  it('writes canonically after edits', () => {
    const next = edit(advancement, (draft) => {
      renameCriterion(draft, 'found', 'seen')
      setDisplay(draft, 'frame', 'challenge')
    })
    const written = canonicalizeModel('advancement', 'advancements/shiny.json', next)
    expect(written.problems).toEqual([])
    expect(JSON.parse(written.text!)).toMatchObject({
      display: { frame: 'challenge' },
      criteria: { seen: { trigger: 'minecraft:inventory_changed' }, given: {} },
    })
  })
})
