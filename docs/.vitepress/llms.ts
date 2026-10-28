import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'

// llms.txt (https://llmstxt.org) for the built site: what a chat assistant or
// a coding agent reads when it's pointed at the docs instead of a project.
//
// - `/llms.txt`       a title, a summary and a link per page, by section
// - `/llms-full.txt`  every page's Markdown in that order, in one file
// - `/<page>.md`      each page's Markdown next to its HTML, which llms.txt links to
//
// Pages are the Markdown sources as written (minus frontmatter). The sidebars
// decide what's in and in what order, so a page added there is added here.

export interface LlmsPage {
  /** Source path under docs/: `guide/scripting.md`. */
  file: string
  title: string
}

export interface LlmsSection {
  title: string
  pages: LlmsPage[]
}

const stripFrontmatter = (text: string) => text.replace(/^---\n[\s\S]*?\n---\n+/, '')

/** A page's first paragraph after its heading, on one line, as the link's note. */
function summaryOf(text: string): string {
  const body = stripFrontmatter(text)
    .replace(/^<!--[\s\S]*?-->\s*/, '')
    .replace(/^# .*\n+/, '')
  const paragraph = body.split(/\n\s*\n/)[0] ?? ''
  if (/^(#|```|:::|\||- |\d+\. )/.test(paragraph.trim())) return ''
  return paragraph.replace(/\s+/g, ' ').trim()
}

export function writeLlms(options: {
  docs: string
  outDir: string
  /** Absolute site URL or base path, ending in `/`. */
  base: string
  title: string
  description: string
  sections: LlmsSection[]
}) {
  const { docs, outDir, base, sections } = options
  const index = [`# ${options.title}`, '', `> ${options.description}`, '']
  const full = [`# ${options.title}`, '', `> ${options.description}`, '']
  for (const section of sections) {
    index.push(`## ${section.title}`, '')
    for (const page of section.pages) {
      const text = readFileSync(path.join(docs, page.file), 'utf8')
      const summary = summaryOf(text)
      index.push(`- [${page.title}](${base}${page.file})${summary ? `: ${summary}` : ''}`)
      const target = path.join(outDir, page.file)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, stripFrontmatter(text))
      full.push(`<!-- ${base}${page.file} -->`, '', stripFrontmatter(text).trimEnd(), '')
    }
    index.push('')
  }
  writeFileSync(path.join(outDir, 'llms.txt'), index.join('\n'))
  writeFileSync(path.join(outDir, 'llms-full.txt'), full.join('\n'))
}
