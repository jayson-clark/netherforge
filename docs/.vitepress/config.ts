import { existsSync, readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, type DefaultTheme } from 'vitepress'
import { writeLlms, type LlmsSection } from './llms'

// The NetherForge docs site. Three kinds of page live here:
//
// - guide/      hand-written, about behaviour that exists, for people making projects
// - format/     the file-format spec (also read on GitHub, so plain relative links); a new
//               page goes in a section of formatSidebar
// - reference/  generated from packages/api/src/spec by `pnpm generate`; never edited here.
//               Its sidebar's sections are read from its own index (the generator's
//               layout.ts), so a new API page shows up without touching this file.
//
// All of it is written for people using NetherForge, not working on it: how the engine
// does something belongs in the skills and the code, not here.
//
// Dead links fail the build (VitePress's default); keep it that way.

const docs = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * `owner/name` on GitHub. CI passes the real one. Pages link to the default
 * (`https://github.com/jayson-clark/netherforge/...`, readable on GitHub too), and
 * the build rewrites those links to the real repository.
 */
const DEFAULT_GITHUB = 'https://github.com/jayson-clark/netherforge'
const repo = process.env.DOCS_REPO || 'jayson-clark/netherforge'
const github = `https://github.com/${repo}`

/** The first `# Heading` of a Markdown file, or its file name. */
function titleOf(file: string): string {
  const text = readFileSync(path.join(docs, file), 'utf8')
  return text.match(/^# (.+)$/m)?.[1].trim() ?? path.basename(file, '.md')
}

function link(file: string): string {
  return '/' + file.replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '')
}

/**
 * The generated API reference, in the sections and order its own index lists it
 * (`## Section`, then `- [Title](file.md): …`), then anything the index doesn't mention.
 */
function referenceSidebar(): DefaultTheme.SidebarItem[] {
  const folder = path.join(docs, 'reference')
  if (!existsSync(folder)) return []
  const files = readdirSync(folder)
    .filter((f) => f.endsWith('.md'))
    .sort()
  const sections: DefaultTheme.SidebarItem[] = []
  const listed = new Set<string>()
  if (files.includes('index.md')) {
    let section: DefaultTheme.SidebarItem | null = null
    for (const line of readFileSync(path.join(folder, 'index.md'), 'utf8').split('\n')) {
      const heading = /^## (.+)$/.exec(line)
      if (heading) {
        section = { text: heading[1].trim(), collapsed: false, items: [] }
        continue
      }
      const entry = /^- \[([^\]]+)\]\(([\w.-]+\.md)\)/.exec(line)
      if (entry && section && files.includes(entry[2])) {
        if (!section.items!.length) sections.push(section)
        section.items!.push({ text: entry[1], link: link(`reference/${entry[2]}`) })
        listed.add(entry[2])
      }
    }
  }
  const rest = files
    .filter((f) => f !== 'index.md' && !listed.has(f))
    .map((file) => ({ text: titleOf(`reference/${file}`), link: link(`reference/${file}`) }))
  return [
    ...(files.includes('index.md') ? [{ text: 'Overview', link: '/reference/' }] : []),
    ...sections,
    ...(rest.length ? [{ text: 'Other', items: rest }] : []),
  ]
}

/** The format spec, in sections: the project first, then the kinds of file by what they're for. */
function formatSidebar(): DefaultTheme.SidebarItem[] {
  const sections: [string, string[]][] = [
    [
      'The project',
      [
        'project.md',
        'references.md',
        'packages.md',
        'workspace.md',
        'settings.md',
        'migrations.md',
        'wasm.md',
        'problems.md',
      ],
    ],
    ['Centities and effects', ['centity.md', 'particle-effect.md', 'cutscene.md']],
    [
      'Menus, dialogs and looks',
      ['menu.md', 'dialog.md', 'resource-pack.md', 'screen.md', 'screen-font.md', 'video.md'],
    ],
    [
      'Items and gameplay',
      ['item.md', 'block.md', 'game-block.md', 'recipe.md', 'loot.md', 'buff.md', 'advancement.md'],
    ],
    [
      'Worlds',
      ['worlds.md', 'terrain.md', 'biome.md', 'sky.md', 'dimension-type.md', 'datapack.md'],
    ],
  ]
  const files = readdirSync(path.join(docs, 'format')).filter(
    (f) => f.endsWith('.md') && f !== 'index.md',
  )
  const listed = sections.flatMap(([, pages]) => pages)
  const rest = files.filter((f) => !listed.includes(f)).sort()
  const item = (file: string) => ({ text: titleOf(`format/${file}`), link: link(`format/${file}`) })
  return [
    { text: 'Overview', link: '/format/' },
    ...sections.map(([text, pages]) => ({
      text,
      items: pages.filter((f) => files.includes(f)).map(item),
    })),
    ...(rest.length ? [{ text: 'Other', items: rest.map(item) }] : []),
  ]
}

const guide: DefaultTheme.SidebarItem[] = [
  {
    text: 'Getting started',
    items: [
      { text: 'What is NetherForge?', link: '/guide/' },
      { text: 'Install the editor', link: '/guide/install' },
      { text: 'Your first project', link: '/guide/first-project' },
      { text: 'Your first centity', link: '/guide/first-centity' },
      { text: 'Your first script', link: '/guide/first-script' },
    ],
  },
  {
    text: 'Building things',
    items: [
      { text: 'Centities and animation', link: '/guide/centities' },
      { text: 'Physics', link: '/guide/physics' },
      { text: 'Particle effects', link: '/guide/particles' },
      { text: 'Cutscenes', link: '/guide/cutscenes' },
      { text: 'Screens and videos', link: '/guide/screens' },
      { text: 'Menus', link: '/guide/menus' },
      { text: 'Dialogs', link: '/guide/dialogs' },
      { text: 'Items, recipes and loot', link: '/guide/items' },
      { text: 'Custom blocks', link: '/guide/blocks' },
      { text: 'Resource packs', link: '/guide/resource-packs' },
      { text: 'World generation', link: '/guide/world-generation' },
    ],
  },
  {
    text: 'Scripting',
    items: [
      { text: 'Scripting basics', link: '/guide/scripting' },
      { text: 'Modules and commands', link: '/guide/modules' },
      { text: 'Players, entities and inventories', link: '/guide/players-and-entities' },
      { text: 'The world', link: '/guide/world' },
      { text: 'Web services', link: '/guide/web' },
      { text: 'WebAssembly', link: '/guide/wasm' },
      { text: 'Server networks', link: '/guide/network' },
      { text: 'Limits and performance', link: '/guide/performance' },
      { text: 'Testing your scripts', link: '/guide/testing' },
      { text: 'Debugging scripts', link: '/guide/debugging' },
    ],
  },
  {
    text: 'Working on a project',
    items: [
      { text: 'Hot reload and the dev loop', link: '/guide/dev-loop' },
      { text: 'Starting from a template', link: '/guide/templates' },
      { text: 'Packages and workspaces', link: '/guide/packages' },
      { text: 'Using git with a project', link: '/guide/git' },
      { text: 'Editing outside the editor', link: '/guide/external-editors' },
      { text: 'Using coding agents', link: '/guide/agents' },
    ],
  },
  {
    text: 'Running a server',
    items: [
      { text: 'Deploying to a server', link: '/guide/deploying' },
      { text: 'Server configuration', link: '/guide/server-config' },
    ],
  },
  {
    text: 'Examples',
    items: [{ text: 'The showcase', link: '/guide/showcase' }],
  },
]

/** `/guide/` → `guide/index.md`, `/guide/install` → `guide/install.md`. */
function fileOf(link: string): string {
  const relative = link.replace(/^\//, '')
  return relative === '' || relative.endsWith('/') ? `${relative}index.md` : `${relative}.md`
}

/** The pages of sidebar groups, in order. */
function pagesOf(items: DefaultTheme.SidebarItem[]): LlmsSection['pages'] {
  return items.flatMap((item) => [
    ...(item.link ? [{ file: fileOf(item.link), title: item.text ?? item.link }] : []),
    ...pagesOf(item.items ?? []),
  ])
}

const title = 'NetherForge'
const description =
  'A desktop editor and Paper plugin for Minecraft server content: scripted entities, Lua modules, menus, dialogs and resource packs.'
const base = process.env.DOCS_BASE || '/'

export default defineConfig({
  title,
  description,
  lang: 'en-US',
  base,
  cleanUrls: true,
  head: [['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}logo.svg` }]],

  themeConfig: {
    logo: '/logo.svg',
    nav: [
      { text: 'Guide', link: '/guide/', activeMatch: '^/guide/' },
      { text: 'File format', link: '/format/', activeMatch: '^/format/' },
      { text: 'Lua API', link: '/reference/', activeMatch: '^/reference/' },
      { text: 'Contributing', link: '/contributing' },
      { text: 'Download', link: `${github}/releases/latest` },
    ],
    sidebar: {
      '/guide/': guide,
      '/format/': formatSidebar(),
      '/reference/': referenceSidebar(),
      '/contributing': guide,
    },
    outline: [2, 3],
    search: { provider: 'local' },
    socialLinks: [{ icon: 'github', link: github }],
    editLink: {
      pattern: `${github}/edit/main/docs/:path`,
      text: 'Edit this page on GitHub',
    },
    footer: {
      message:
        'Apache-2.0. Not an official Minecraft product; not approved by or associated with Mojang or Microsoft.',
    },
  },

  // llms.txt, llms-full.txt and every page's Markdown, for AI tools (see llms.ts).
  // DOCS_URL makes its links absolute, as llms.txt readers expect.
  buildEnd(site) {
    writeLlms({
      docs,
      outDir: site.outDir,
      base: (process.env.DOCS_URL || base).replace(/\/?$/, '/'),
      title,
      description,
      sections: [
        { title: 'Guide', pages: pagesOf(guide) },
        { title: 'File format', pages: pagesOf(formatSidebar()) },
        { title: 'Lua API reference', pages: pagesOf(referenceSidebar()) },
      ],
    })
  },

  // Reference pages are generated from packages/api/src/spec: editing them here would be
  // overwritten, so they get no edit link.
  transformPageData(pageData) {
    if (pageData.relativePath.startsWith('reference/')) {
      pageData.frontmatter.editLink = false
    }
  },

  markdown: {
    theme: { light: 'github-light', dark: 'github-dark' },
    config(md) {
      const render = md.renderer.rules.link_open
      md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
        const href = tokens[idx].attrGet('href')
        if (href?.startsWith(DEFAULT_GITHUB)) {
          tokens[idx].attrSet('href', github + href.slice(DEFAULT_GITHUB.length))
        }
        return render
          ? render(tokens, idx, options, env, self)
          : self.renderToken(tokens, idx, options)
      }
    },
  },
})
