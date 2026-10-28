/**
 * The project templates: "minigame", "shop" and "RPG mob", each a small
 * project (`examples/template_<id>`) whose files the editor copies into a
 * project as its own, to change as it likes. They're the same files the
 * repository's tests run (the test runner runs each one's `tests/`, so an API
 * change that breaks one fails CI), bundled here at build time, tests
 * included: a copy keeps them, as examples of testing its own.
 */
import type { ProjectManifest } from './format'

type ProjectRequires = NonNullable<ProjectManifest['requires']>

export interface ProjectTemplate {
  /** `minigame`, `shop`, `rpg_mob`: the example's folder is `template_<id>`. */
  id: string
  title: string
  /** What it is and what it shows, in a line. */
  summary: string
  /** Its files by path inside it, `netherforge.json` left out: what a project gets. */
  files: Record<string, string>
  /** What its `netherforge.json` requires, which the project then requires too. */
  requires: ProjectRequires
}

// What a template's .gitignore keeps out of the repo (the editor's own state, and the default font's
// advances it writes from an imported client) stays out of the bundle too.
const sources = import.meta.glob(
  [
    '../../../../examples/template_*/**/*',
    '!../../../../examples/template_*/.netherforge/**',
    '!../../../../examples/template_*/.gitignore',
    '!../../../../examples/template_*/fonts/default.json',
  ],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>

const PREFIX = '../../../../examples/template_'

/** What each says about itself: the rest (the files) comes from the example. */
const ABOUT: Record<string, Pick<ProjectTemplate, 'title' | 'summary'>> = {
  minigame: {
    title: 'Minigame',
    summary:
      'A lobby, a countdown and timed rounds between two teams, with a sidebar and boss bar.',
  },
  shop: {
    title: 'Shop',
    summary: 'A menu that sells items for coins kept per player in a database.',
  },
  rpg_mob: {
    title: 'RPG mob',
    summary: 'A goblin that spawns by itself, can be hurt and killed, and drops loot.',
  },
}

function filesOf(id: string): Record<string, string> {
  const prefix = `${PREFIX}${id}/`
  return Object.fromEntries(
    Object.entries(sources)
      .filter(([path]) => path.startsWith(prefix))
      .map(([path, text]) => [path.slice(prefix.length), text] as const)
      // `.luarc.json` is the example's own LuaLS setup and `netherforge.json` its manifest: the
      // project has its own of both.
      .filter(([path]) => path !== '.luarc.json' && path !== 'netherforge.json')
      .sort(([a], [b]) => a.localeCompare(b)),
  )
}

function requiresOf(id: string): ProjectRequires {
  const manifest = JSON.parse(sources[`${PREFIX}${id}/netherforge.json`]!) as {
    requires?: ProjectRequires
  }
  return manifest.requires ?? {}
}

export const TEMPLATES: ProjectTemplate[] = Object.entries(ABOUT).map(([id, about]) => ({
  id,
  ...about,
  files: filesOf(id),
  requires: requiresOf(id),
}))

export const templateById = (id: string) => TEMPLATES.find((it) => it.id === id)
