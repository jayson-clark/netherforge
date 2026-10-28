/** `docs/reference/*.md`: the API reference. */
import { articles } from '../../src/spec/articles.ts'
import type {
  ApiSpec,
  Article,
  EventSpec,
  Field,
  Fn,
  LuaClass,
  Surface,
  ValueType,
} from '../../src/types.ts'
import { REQUIREMENTS, requirementKind, requirementSentence } from '../../src/requirements.ts'
import type { Requirement } from '../../src/types.ts'
import { chainOf } from './bindings.ts'
import { GENERATED_BY, SAVEABLE, WAITS, eventedClasses, sinceVersion } from './common.ts'
import { CATEGORIES, EVENT_FUNCTIONS, EVENT_GROUPS, FUNCTION_GROUPS, type Group } from './layout.ts'

const header = `<!-- ${GENERATED_BY} -->\n`

/** Where each name a type can mention is documented: `Player` → `player.md`. */
type Links = Map<string, string>

const NO_LINKS: Links = new Map()

/** How a function is called: `nf.centities.spawn(kind, x, y, z, world?)`, `centity:node(name)`. */
function callShape(receiver: string, separator: string, fn: Fn): string {
  const params = fn.params.map((p) => (p.optional ? `${p.name}?` : p.name)).join(', ')
  return `${receiver}${separator}${fn.name}(${params})`
}

function code(text: string): string {
  return '`' + text.replace(/\|/g, '\\|') + '`'
}

function cell(text: string): string {
  return text.replace(/\|/g, '\\|').replace(/\n/g, ' ')
}

/**
 * A type as Markdown, each name in it that has a page linked to it: `Player?` is
 * ``[`Player`](player.md)`?` ``. Safe in a table cell.
 */
export function typeMarkdown(type: string, links: Links): string {
  const parts: string[] = []
  let plain = ''
  for (const token of type.match(/[A-Za-z_][\w.]*|[^A-Za-z_]+/g) ?? []) {
    const target = links.get(token)
    if (!target) {
      plain += token
      continue
    }
    if (plain) parts.push(code(plain))
    plain = ''
    parts.push(`[${code(token)}](${target})`)
  }
  if (plain) parts.push(code(plain))
  return parts.join('')
}

/** A doc's first sentence, for a summary. */
function firstSentence(doc: string): string {
  const match = /^([\s\S]*?[.!?])(\s|$)/.exec(doc.trim())
  return (match?.[1] ?? doc).replace(/\s+/g, ' ')
}

export function fnDoc(
  receiver: string,
  separator: string,
  fn: Fn,
  level = '##',
  links: Links = NO_LINKS,
  anchor?: string,
): string {
  const id = anchor ? ` {#${anchor}}` : ''
  const parts = [`${level} \`${callShape(receiver, separator, fn)}\`${id}`, '', fn.doc, '']
  if (fn.waits) parts.push(`*${WAITS}*`, '')
  if (fn.since) parts.push(`*Since Minecraft ${sinceVersion(fn.since)}.*`, '')
  if (fn.requires)
    parts.push(`*${requirementSentence(fn.requires, `${receiver}${separator}${fn.name}`)}*`, '')
  if (fn.params.length) {
    parts.push('| Parameter | Type | |', '| --- | --- | --- |')
    for (const p of fn.params) {
      parts.push(
        `| \`${p.name}\`${p.optional ? ' (optional)' : ''} | ${typeMarkdown(p.type, links)} | ${cell(p.doc)} |`,
      )
    }
    parts.push('')
  }
  if (fn.returns.length) {
    const returns = fn.returns
      .map((r) => `${typeMarkdown(r.type, links)}${r.doc ? `: ${r.doc}` : ''}`)
      .join(', ')
    parts.push(`**Returns** ${returns}`, '')
  }
  if (fn.example) parts.push('```lua', fn.example, '```', '')
  return parts.join('\n')
}

export function fieldsTable(cls: { fields: Field[] }, links: Links = NO_LINKS): string {
  const rows = ['| Field | Type | |', '| --- | --- | --- |']
  for (const field of cls.fields) {
    const doc = field.since
      ? `${field.doc} *Since Minecraft ${sinceVersion(field.since)}.*`.trim()
      : field.doc
    rows.push(`| \`${field.name}\` | ${typeMarkdown(field.type, links)} | ${cell(doc)} |`)
  }
  return rows.join('\n')
}

function pageName(cls: { name: string }): string {
  return cls.name.toLowerCase()
}

/** The anchor of a shape on the tables page: `types.md#clickevent`. */
const shapeAnchor = (name: string) => name.toLowerCase()

/** Every name a type can mention that has somewhere to link to, as seen from a reference page. */
function linksOf(spec: ApiSpec): Links {
  const links: Links = new Map()
  for (const cls of spec.classes) links.set(cls.name, `${pageName(cls)}.md`)
  for (const value of spec.values ?? []) links.set(value.name, `${pageName(value)}.md`)
  for (const shape of spec.shapes) links.set(shape.name, `types.md#${shapeAnchor(shape.name)}`)
  for (const alias of spec.aliases ?? []) links.set(alias.name, 'index.md#types')
  return links
}

/** The lowercase name examples use for an object: `centity:node(...)`, `player:send_message(...)`. */
function receiver(cls: LuaClass): string {
  return cls.methods ? cls.name.toLowerCase() : cls.name
}

/** A payload's link on the tables page, or "`Event`" for an event without one. */
function payloadLink(event: EventSpec): string {
  const name = event.payload ?? 'Event'
  return `[${name}](types.md#${shapeAnchor(name)})`
}

/** A class's events as a table: what `:on` accepts on it. */
function eventsTable(events: EventSpec[]): string {
  const rows = ['| Event | Payload | |', '| --- | --- | --- |']
  for (const event of events) {
    const flags = [
      event.cancellable ? '**Cancellable.**' : '',
      event.bubbles ? 'Bubbles.' : '',
      event.writable?.length
        ? `Writable: ${event.writable.map((it) => `\`${it}\``).join(', ')}.`
        : '',
      event.options?.length ? `Options: ${event.options.map((it) => `\`${it}\``).join(', ')}.` : '',
      event.local ? 'Only the registering script hears it.' : '',
      event.since ? `*Since Minecraft ${sinceVersion(event.since)}.*` : '',
    ].filter(Boolean)
    rows.push(
      `| \`${event.name}\` | ${payloadLink(event)} | ${cell([event.doc, ...flags].join(' '))} |`,
    )
  }
  return rows.join('\n')
}

/** A class's events, a table per group when it has groups (at heading [level]). */
function groupedEvents(cls: LuaClass & { events: EventSpec[] }, level: string): string[] {
  const groups = EVENT_GROUPS[cls.name]
  if (!groups) return [eventsTable(cls.events), '']
  const byName = new Map(cls.events.map((it) => [it.name, it]))
  return groups.flatMap((group) => [
    `${level} ${group.title}`,
    '',
    eventsTable(group.names.map((name) => byName.get(name)!)),
    '',
  ])
}

/** `[Living](living.md)`. */
const classLink = (cls: { name: string }) => `[${cls.name}](${pageName(cls)}.md)`

/** `a`, or `an` before `Entity`. */
const article = (name: string) => (/^[AEIOU]/.test(name) ? 'an' : 'a')

/** What a handle class is a kind of (up its chain), and the kinds there are of it. */
function chainText(spec: ApiSpec, cls: LuaClass): string {
  const above = chainOf(spec, cls).slice(1)
  const below = spec.classes.filter((it) => it.extends === cls.name)
  const parts: string[] = []
  if (above.length) {
    const kinds = above
      .map((it, k) => `${k ? 'which is ' : ''}${article(it.name)} ${classLink(it)}`)
      .join(', ')
    parts.push(
      `It is ${kinds}, so it has every method and event of ${above.length === 1 ? 'that class' : 'those classes'} too, and you can pass it wherever ${above.map((it) => `${article(it.name)} \`${it.name}\``).join(' or ')} is expected.`,
    )
  }
  if (below.length)
    parts.push(
      `More specific kinds: ${below.map(classLink).join(', ')}. You always get the most specific kind the server knows it to be.`,
    )
  return parts.length ? parts.join(' ') + '\n' : ''
}

/** A class's functions in sections: its groups, or one section of them all. */
function sectionsOf(cls: LuaClass): Group[] {
  const own = cls.functions.filter((it) => !EVENT_FUNCTIONS.has(it.name))
  return (
    FUNCTION_GROUPS[cls.name] ?? [
      { title: cls.methods ? 'Methods' : 'Functions', names: own.map((it) => it.name) },
    ]
  ).filter((it) => it.names.length)
}

/** A section's anchor: its title's, unless a function on the page already has that one. */
function sectionAnchor(cls: LuaClass, section: Group): string {
  const anchor = slug(section.title)
  return cls.functions.some((it) => it.name === anchor) ? `${anchor}-section` : anchor
}

/** A short table of every function on the page, by section, and what a subclass inherits. */
function overview(spec: ApiSpec, cls: LuaClass, sections: Group[]): string[] {
  const linked = (names: string[], page = '') =>
    names.map((name) => `[${code(name)}](${page}#${name})`).join(' ')
  const rows = sections.map(
    (it) => `| [${it.title}](#${sectionAnchor(cls, it)}) | ${linked(it.names)} |`,
  )
  const events = cls.functions.filter((it) => EVENT_FUNCTIONS.has(it.name)).map((it) => it.name)
  if (cls.events?.length || events.length)
    rows.push(
      `| [Events](#events) | ${linked(events)} ${cls.events?.length ? `and ${cls.events.length} events` : ''} |`,
    )
  for (const parent of chainOf(spec, cls).slice(1)) {
    const names = parent.functions
      .map((it) => it.name)
      .filter((it) => !EVENT_FUNCTIONS.has(it) && !cls.functions.some((own) => own.name === it))
    if (names.length)
      rows.push(`| From ${classLink(parent)} | ${linked(names, `${pageName(parent)}.md`)} |`)
  }
  const total = sections.reduce((n, it) => n + it.names.length, 0)
  if (total < 6 && rows.length <= 2) return []
  return [`| | ${cls.methods ? 'Methods' : 'Functions'} |`, '| --- | --- |', ...rows, '']
}

/** The anchor VitePress gives a heading of plain words: `Who they are` → `who-they-are`. */
function slug(title: string): string {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
}

function classPage(spec: ApiSpec, cls: LuaClass, links: Links): string {
  const separator = cls.methods ? ':' : '.'
  const intro = cls.methods
    ? `A handle: call its methods with a colon, \`${receiver(cls)}:method()\`.`
    : 'A table of functions: call them with a dot.'
  const sections = sectionsOf(cls)
  const byName = new Map(cls.functions.map((it) => [it.name, it]))
  const parts = [header, `# ${cls.name}`, '', cls.doc, '', intro, '']
  if (cls.methods) parts.push(chainText(spec, cls))
  if (cls.saveable) parts.push(`${SAVEABLE}\n`)
  parts.push(...overview(spec, cls, sections))
  if (cls.name === 'nf') {
    parts.push('## Namespaces', '', '| Namespace | |', '| --- | --- |')
    for (const field of cls.fields)
      parts.push(
        `| ${links.get(field.type) ? `[${code(field.type)}](${links.get(field.type)})` : code(field.type)} | ${cell(firstSentence(field.doc))} |`,
      )
    parts.push('')
  } else if (cls.fields.length) {
    parts.push('## Fields', '', fieldsTable(cls, links), '')
  }
  for (const section of sections) {
    parts.push(`## ${section.title} {#${sectionAnchor(cls, section)}}`, '')
    for (const name of section.names)
      parts.push(fnDoc(receiver(cls), separator, byName.get(name)!, '###', links, name))
  }
  const eventFns = cls.functions.filter((it) => EVENT_FUNCTIONS.has(it.name))
  if (cls.events?.length || eventFns.length) {
    parts.push('## Events', '')
    if (cls.events?.length)
      parts.push(
        `What \`${receiver(cls)}${separator}on(event, handler)\` listens for. See [Events](events.md) for how events work.${cls.customEvents ? ' Any name with a `:` in it is a custom event, raised with `emit`.' : ''}`,
        '',
        ...groupedEvents(cls as LuaClass & { events: EventSpec[] }, '###'),
      )
    for (const fn of eventFns)
      parts.push(fnDoc(receiver(cls), separator, fn, '###', links, fn.name))
  }
  return parts.join('\n')
}

const OPERATOR_SYMBOLS = { add: '+', sub: '-', mul: '*', div: '/', unm: '-' } as const

function valuePage(value: ValueType, links: Links): string {
  // `a:length()`: the plan's own name for a vector, and not the library's (`vec3`).
  const self = value.library ? 'a' : value.name.toLowerCase()
  const parts = [
    header,
    `# ${value.name}`,
    '',
    value.doc,
    '',
    `A value: read its fields with a dot (\`${self}.${value.fields[0]!.name}\`), call its methods with a colon (\`${self}:method()\`). Its fields can't be assigned.`,
    '',
    '## Fields',
    '',
    fieldsTable(value, links),
    '',
  ]
  if (value.operators.length) {
    parts.push('## Operators', '', '| Expression | Result | |', '| --- | --- | --- |')
    for (const it of value.operators) {
      const symbol = OPERATOR_SYMBOLS[it.op]
      const expression = it.operand ? `${self} ${symbol} ${it.operand}` : `${symbol}${self}`
      parts.push(`| ${code(expression)} | ${typeMarkdown(it.result, links)} | ${cell(it.doc)} |`)
    }
    parts.push('')
  }
  const library = value.library
  if (library) {
    parts.push(`## Making one: \`${library.name}\``, '', library.doc, '')
    parts.push(fnDoc('', '', library.call, '###', links))
    parts.push('### Constants', '', fieldsTable(library, links), '')
    for (const fn of library.functions)
      parts.push(fnDoc(library.name, '.', fn, '###', links, `${library.name}-${fn.name}`))
  }
  parts.push('## Methods', '')
  for (const fn of value.functions) parts.push(fnDoc(self, ':', fn, '###', links, fn.name))
  return parts.join('\n')
}

function surfacePage(surfaces: Surface[], links: Links): string {
  const parts = [
    header,
    '# Scripts',
    '',
    "Where a script runs decides what it can see. Every script also gets the [globals](index.md#globals). A centity, a menu, a dialog, an item and a block each have at most one script, and its own handle is `this`; a slot or a button is `this:slot(index)` or `this:button(key)`. A script can `require` more `.lua` files beside it, which run as part of it, with the same `this`. A script's body runs every time it starts, and that's where it registers its handlers: see [Events](events.md).",
    '',
    'A script the editor creates starts with a line like `local this = this --[[@as Centity]]`, which tells the editor (and lua-language-server) which kind of `this` it is, so completions are right.',
    '',
  ]
  for (const surface of surfaces) {
    parts.push(
      `## ${surface.name[0]!.toUpperCase()}${surface.name.slice(1)} scripts`,
      '',
      surface.doc,
      '',
    )
    if (surface.globals.length) {
      parts.push('| Global | Type | |', '| --- | --- | --- |')
      for (const g of surface.globals)
        parts.push(`| \`${g.name}\` | ${typeMarkdown(g.type, links)} | ${cell(g.doc)} |`)
      parts.push('')
    }
  }
  return parts.join('\n')
}

const EVENTS_INTRO = [
  'Events live on the handle they are about: `this:on("tick", handler)`, `node:on("click", handler)`, `menu:slot(13):on("click", handler)`. Server-wide events are on `nf`: `nf.on("player_join", handler)`. Every evented class also has `once`, which listens for the next one only. An event a class does not have is an error.',
  '',
  '```lua',
  'local lid = this:node("lid")',
  'lid:on("click", function(event)',
  '  this:play_animation("open")',
  '  event:stop()',
  'end)',
  'local subscription = this:on("tick", function(event) end, { every = 20 })',
  'subscription:cancel()',
  '```',
  '',
  '## The event object',
  '',
  "A handler gets one table: the event's payload fields (each event's payload is linked below, and listed on [Tables](types.md)) and the members of [Event](types.md#event): `name`, `current`, `cancelled`, `stopped`, `stop()`, `cancel()` and `uncancel()`. **What a handler returns means nothing.** `event:cancel()` cancels what caused a cancellable event and is an error on any other. Fields an event lists as writable may be assigned, and take effect once every handler has run; assigning any other field is an error.",
  '',
  '## Bubbling',
  '',
  'Some events go on along a path after the handle they happened to. A centity click is heard by the clicked node, then each node above it, then the centity, then `nf.on("centity_click")`. A menu click: the slot, the menu, the template the menu was made from (when a script made it with `nf.menus.create`), then `nf.on("menu_click")`. A dialog press: the button, the dialog, then `nf.on("dialog_press")`. A menu\'s `open` and `close` go on, through its template if it has one, to `menu_open` and `menu_close`. `event:stop()` ends it anywhere, after the handlers on the same handle registered before. `event.target` is what was hit; `event.current` is the handle whose handler is running.',
  '',
  '## Lifetimes',
  '',
  "A handler lives as long as **both** the script that registered it and the handle it is on: a module's handler on a centity's node goes when the module unloads or the centity is removed, whichever comes first, and one on a menu window goes with the window. A script's body runs every time it starts, so it registers its handlers afresh each time.",
  '',
  '## Custom events',
  '',
  'A name with a `:` in it is a custom event, never an error: `nf.emit("shop:purchased", payload)` reaches every `nf.on("shop:purchased")`, and `centity:emit("door:open", payload)` every `centity:on("door:open")` on that instance, whoever registered them. Delivery is immediate, the payload is handed over as it is, and `emit` returns whether a handler cancelled it.',
  '',
  '## Errors',
  '',
  "A handler that errors is logged with its file and line (in the console and the editor), and the other handlers still run. The same error repeated is logged only now and then. After 20 errors in a row a subscription is cancelled, with a line saying so; a success resets the count. Tasks from `nf.every` stop the same way. An error in a script's body fails the whole script, because its setup didn't finish.",
  '',
]

function eventsPage(spec: ApiSpec): string {
  const parts = [header, '# Events', '', ...EVENTS_INTRO, '# Every event', '']
  for (const cls of eventedClasses(spec)) {
    const where = cls.name === 'nf' ? '`nf.on`' : `[${cls.name}](${pageName(cls)}.md)`
    parts.push(`## On ${cls.name}`, '', `What ${where} listens for.`, '')
    parts.push(...groupedEvents(cls, '###'))
    for (const event of cls.events.filter((it) => it.example)) {
      parts.push(`#### Example: ${cls.name === 'nf' ? 'nf' : receiver(cls)} ${event.name}`, '')
      parts.push('```lua', event.example!, '```', '')
    }
  }
  return parts.join('\n')
}

/** The category title of each page, by class, value or file name. */
function categoryOf(): Map<string, string> {
  return new Map(CATEGORIES.flatMap((it) => it.pages.map((page) => [page, it.title] as const)))
}

/** The category of the tables an event on `nf` hands its handlers, by what the event is about. */
function nfEventCategory(event: string): string {
  const about: [RegExp, string][] = [
    [/^(player|entity|projectile|vehicle)_/, 'Players and entities'],
    [/^(block|leaves|piston|sign|world|weather|thunder|lightning|chunk)_/, 'Worlds and blocks'],
    [/^centity_/, 'Centities and effects'],
    [/^(menu|dialog)_/, 'Menus and dialogs'],
    [/^(recipe|item|furnace)_/, 'Items and inventories'],
  ]
  return about.find(([pattern]) => pattern.test(event))?.[1] ?? 'Core'
}

/**
 * The tables page: every shape, under the category of the first class (in the reference's
 * order) that takes, returns or hears it, directly or inside another table.
 */
function typesPage(spec: ApiSpec, links: Links): string {
  const shapes = new Map(spec.shapes.map((it) => [it.name, it]))
  const names = (type: string) =>
    (type.match(/[A-Za-z_][\w.]*/g) ?? []).filter((it) => shapes.has(it))
  const categories = categoryOf()
  const home = new Map<string, string>()
  const place = (shape: string, category: string) => {
    if (home.has(shape)) return
    home.set(shape, category)
    const it = shapes.get(shape)!
    if (it.extends && shapes.has(it.extends)) place(it.extends, category)
    for (const field of it.fields) for (const name of names(field.type)) place(name, category)
    for (const fn of it.functions)
      for (const t of [...fn.params.map((p) => p.type), ...fn.returns.map((r) => r.type)])
        for (const name of names(t)) place(name, category)
  }
  if (shapes.has('Event')) place('Event', 'Basics')
  const ordered = [...spec.classes].sort((a, b) => {
    const rank = (cls: LuaClass) => {
      const title = categories.get(cls.name)
      const index = CATEGORIES.findIndex((it) => it.title === title)
      return index < 0 ? CATEGORIES.length : index
    }
    // `nf` hears nearly every event, so its payloads are placed last, by what each event is about.
    return (a.name === 'nf' ? 1 : 0) - (b.name === 'nf' ? 1 : 0) || rank(a) - rank(b)
  })
  for (const cls of ordered) {
    const category = categories.get(cls.name) ?? 'Other'
    for (const event of cls.events ?? [])
      for (const name of names(event.payload ?? ''))
        place(name, cls.name === 'nf' ? nfEventCategory(event.name) : category)
    for (const fn of cls.functions)
      for (const t of [...fn.params.map((p) => p.type), ...fn.returns.map((r) => r.type)])
        for (const name of names(t)) place(name, category)
    for (const field of cls.fields) for (const name of names(field.type)) place(name, category)
  }
  const parts = [
    header,
    '# Tables',
    '',
    "The plain tables the API hands you and takes from you: the payloads event handlers get, the option tables functions take, and the records they return. Each is a Lua table with these fields; a field not marked optional in its type may still be `nil` where its description says so. An option table you pass may leave out any field, and a key that isn't one of its fields is an error.",
    '',
  ]
  const titles = [...CATEGORIES.map((it) => it.title), 'Other']
  for (const title of titles) {
    const inIt = spec.shapes.filter((it) => (home.get(it.name) ?? 'Other') === title)
    if (!inIt.length) continue
    parts.push(`## ${title}`, '')
    for (const shape of inIt) {
      parts.push(`### ${shape.name} {#${shapeAnchor(shape.name)}}`, '', shape.doc, '')
      if (shape.extends)
        parts.push(
          `Has the fields and methods of [${shape.extends}](#${shapeAnchor(shape.extends)}) too.`,
          '',
        )
      if (shape.fields.length) parts.push(fieldsTable(shape, links), '')
      for (const fn of shape.functions) parts.push(fnDoc('event', ':', fn, '####', links))
    }
  }
  return parts.join('\n')
}

/** What the index lists for a page: its link, and what it is. */
function indexEntry(spec: ApiSpec, page: string, titles: Map<string, string>): string | null {
  const article = articles.find((it) => it.file === page)
  if (article) return `- [${article.title}](${article.file}): ${article.summary}`
  if (page.endsWith('.md')) {
    const title = titles.get(page)
    return title
      ? `- [${title.split(': ')[0]}](${page}): ${title.split(': ').slice(1).join(': ')}`
      : null
  }
  const cls = [...spec.classes, ...(spec.values ?? [])].find((it) => it.name === page)
  if (!cls) return null
  return `- [${cls.name}](${pageName(cls)}.md): ${cls.doc.split('. ')[0]!.replace(/\.$/, '')}`
}

/** The pages written here that aren't a class, a value or an article, and how the index describes them. */
const OWN_PAGES = new Map([
  [
    'scripts.md',
    'Scripts: centity, menu, dialog, item and block scripts and modules, and the globals each gets',
  ],
  ['events.md', 'Events: how `:on` works, and every event each class has'],
  ['types.md', 'Tables: event payloads, option tables and the records functions return'],
  ['terrain-scripts.md', 'Terrain scripts: what a terrain generator script can call'],
])

function indexPage(spec: ApiSpec, links: Links): string {
  const parts = [
    header,
    '# Lua API reference',
    '',
    'Everything a NetherForge script can call. Scripts are Lua 5.4. New to scripting NetherForge? Start with [Your first script](../guide/first-script.md) and [Scripting basics](../guide/scripting.md), then come back here to look things up.',
    '',
    'Calls on `nf` and its namespaces use a dot (`nf.players.online()`); calls on a handle, like a player or a centity, use a colon (`player:send_message("hi")`).',
    '',
  ]
  const listed = new Set<string>()
  for (const category of CATEGORIES) {
    const entries = category.pages
      .map((page) => {
        listed.add(page)
        return indexEntry(spec, page, OWN_PAGES)
      })
      .filter((it): it is string => it !== null)
    if (!entries.length) continue
    parts.push(`## ${category.title}`, '', category.doc, '', ...entries, '')
  }
  const rest = [...spec.classes, ...(spec.values ?? [])].filter((it) => !listed.has(it.name))
  if (rest.length)
    parts.push('## Other', '', ...rest.map((it) => indexEntry(spec, it.name, OWN_PAGES)!), '')
  parts.push(
    '## Globals',
    '',
    '| Global | Type | |',
    '| --- | --- | --- |',
    ...spec.globals.map(
      (g) => `| \`${g.name}\` | ${typeMarkdown(g.type, links)} | ${cell(g.doc)} |`,
    ),
    '',
    ...(spec.aliases?.length
      ? [
          '## Types',
          '',
          'Names the reference uses for a plain Lua type that means more than it.',
          '',
          '| Type | Is a | |',
          '| --- | --- | --- |',
          ...spec.aliases.map((it) => `| \`${it.name}\` | ${code(it.type)} | ${cell(it.doc)} |`),
          '',
        ]
      : []),
    '## The Lua standard library',
    '',
    'Also there from Lua itself: `string`, `table`, `math`, `utf8`, `coroutine`, `pairs`, `ipairs`, `pcall`, `error`, `tostring`, `tonumber`, `type`, `select`, `setmetatable` and the rest of the basics. The shared library tables are read-only.',
    '',
    "`math.random` is seeded afresh each time the server starts the project's scripts (at server start, and on a full reload), so its numbers differ from run to run. Every script shares the one generator: `math.randomseed(n)` gives a repeatable sequence, for every script at once. For a generator of your own, see [`nf.random`](nf.random.md).",
    '',
    `Taken away, because they could reach the machine or escape the limits: ${spec.removed.map((name) => `\`${name}\``).join(', ')}. Files go through [\`nf.files\`](nf.files.md), and \`require\` reaches only the project's own Lua.`,
    '',
    'Each call into a script runs under an instruction budget, so a runaway loop is stopped with an error instead of freezing the server.',
    '',
    ...requirementsSection(spec),
    '## Editor support',
    '',
    'The NetherForge editor completes, documents and checks every name here as you type. In another editor, lua-language-server does the same with the stubs the editor writes into each project: see [Editing outside the editor](../guide/external-editors.md#lua-luals-stubs).',
  )
  return parts.join('\n')
}

/**
 * What the functions that need something declared need, and how a package declares it: only
 * the requirements some function has.
 */
function requirementsSection(spec: ApiSpec): string[] {
  const needing = new Map<Requirement, string[]>()
  for (const cls of spec.classes)
    for (const fn of cls.functions)
      if (fn.requires) {
        const separator = cls.methods ? ':' : '.'
        needing.set(fn.requires, [
          ...(needing.get(fn.requires) ?? []),
          `[\`${receiver(cls)}${separator}${fn.name}\`](${pageName(cls)}.md#${fn.name})`,
        ])
      }
  if (needing.size === 0) return []
  const rows = [...needing].map(([requirement, fns]) => {
    const spec = REQUIREMENTS[requirementKind(requirement)!]
    const declared = spec.declared!(requirement)
    return `| \`${requirement}\` | ${cell(spec.what)} | ${cell(declared)} | ${fns.join(', ')} |`
  })
  return [
    '## Requirements',
    '',
    'Some functions only work once the project has declared that it uses them, so whoever runs it sees in one place what it may do. Calling one without is an error saying what to declare; the editor marks such a call.',
    '',
    '| Requirement | Lets scripts | Declared with | Functions |',
    '| --- | --- | --- | --- |',
    ...rows,
    '',
  ]
}

function articlePage(article: Article): string {
  return [header, `# ${article.title}`, '', article.body.trim(), ''].join('\n')
}

/** Every page of `docs/reference/`, by file name (`terrain-scripts.md` is written beside them). */
export function referencePages(spec: ApiSpec): Map<string, string> {
  const links = linksOf(spec)
  const pages = new Map<string, string>()
  for (const article of articles) pages.set(article.file, articlePage(article))
  for (const cls of spec.classes) pages.set(`${pageName(cls)}.md`, classPage(spec, cls, links))
  for (const value of spec.values ?? []) pages.set(`${pageName(value)}.md`, valuePage(value, links))
  pages.set('scripts.md', surfacePage(spec.surfaces, links))
  pages.set('events.md', eventsPage(spec))
  pages.set('types.md', typesPage(spec, links))
  pages.set('index.md', indexPage(spec, links))
  return pages
}

/** The pages [referencePages] writes that aren't a class, a value or an article. */
export const REFERENCE_OWN_PAGES = [...OWN_PAGES.keys()]
